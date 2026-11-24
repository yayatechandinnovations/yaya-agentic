package com.yayatechandinnovations.yayaagentic.channel.whatsapp.shared;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.api.AdminApiException;
import com.yayatechandinnovations.yayaagentic.channel.binding.BindingStatus;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.ChannelSessionService;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderStatus;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSender;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSenderService;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.keywords.KeywordActionService;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.ChannelBindingEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChannelBindingRepository;
import com.yayatechandinnovations.yayaagentic.persistence.ChannelOptOutRepository;
import com.yayatechandinnovations.yayaagentic.persistence.OutboundPendingEntity;
import com.yayatechandinnovations.yayaagentic.persistence.OutboundPendingRepository;
import com.yayatechandinnovations.yayaagentic.persistence.OutreachDeliveryRepository;
import com.yayatechandinnovations.yayaagentic.persistence.SystemSenderEntity;
import com.yayatechandinnovations.yayaagentic.persistence.SystemSenderRepository;
import com.yayatechandinnovations.yayaagentic.persistence.TenantEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantRepository;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppSenderAssignmentEntity;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppSenderAssignmentEventEntity;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppSenderAssignmentEventRepository;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppSenderAssignmentRepository;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppSharedRouteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Which workspaces a platform sender serves (whatsapp-shared-sender-design.md
 * §5, §11). Platform admins assign, relabel, pause and end; a workspace edits
 * only its own non-member reply.
 */
@Service
public class SenderAssignmentService {

    private static final Logger log = LoggerFactory.getLogger(SenderAssignmentService.class);
    private static final String ENDPOINT_KIND = "WHATSAPP_SENDER";
    public static final int DEFAULT_DAILY_CAP = 1000;
    public static final int MAX_LABEL_CHARS = 60;
    public static final int MAX_REPLY_CHARS = 1000;

    private final WhatsAppSenderAssignmentRepository repo;
    private final WhatsAppSenderAssignmentEventRepository events;
    private final WhatsAppSenderService senders;
    private final TenantRepository tenants;
    private final SenderMemberService members;
    private final KeywordActionService keywords;
    private final ChannelSessionService sessions;
    private final ChannelBindingRepository bindings;
    private final SystemSenderRepository systemSenders;
    private final OutboundPendingRepository parked;
    private final WhatsAppSharedRouteRepository routes;
    private final OutreachDeliveryRepository deliveries;
    private final ChannelOptOutRepository optOuts;
    private final ObjectMapper json;

    public SenderAssignmentService(WhatsAppSenderAssignmentRepository repo,
                                   WhatsAppSenderAssignmentEventRepository events,
                                   WhatsAppSenderService senders,
                                   TenantRepository tenants,
                                   SenderMemberService members,
                                   KeywordActionService keywords,
                                   ChannelSessionService sessions,
                                   ChannelBindingRepository bindings,
                                   SystemSenderRepository systemSenders,
                                   OutboundPendingRepository parked,
                                   WhatsAppSharedRouteRepository routes,
                                   OutreachDeliveryRepository deliveries,
                                   ChannelOptOutRepository optOuts,
                                   ObjectMapper json) {
        this.repo = repo;
        this.events = events;
        this.senders = senders;
        this.tenants = tenants;
        this.members = members;
        this.keywords = keywords;
        this.sessions = sessions;
        this.bindings = bindings;
        this.systemSenders = systemSenders;
        this.parked = parked;
        this.routes = routes;
        this.deliveries = deliveries;
        this.optOuts = optOuts;
        this.json = json;
    }

    /** An assignment with today's numbers, for the platform-admin card and the workspace's own view. */
    public record View(String id, String senderId, String tenantId, String label, String status,
                       int dailyOutboundCap, String nonMemberReply, String assignedBy, Instant assignedAt,
                       Instant endedAt, long templateSends24h, long optOuts7d, long members) {}

    /** What ending an assignment closes in the workspace (§11); a dry run computes it without writing. */
    public record EndEffects(List<String> systemSendersUnlinked, int bindingsDeactivated, int keywordActionsDeleted,
                             long membersDeleted, int sessionsClosed, int parkedOutboundDropped, int routesCleared) {}

    public record EndOutcome(boolean dryRun, EndEffects effects) {}

    /* ------------------------------- read --------------------------------- */

    public List<View> listForSender(SenderId sender) {
        senders.requireForTenant(null, sender);
        return repo.findLiveBySender(sender.value()).stream().map(this::view).toList();
    }

    public long countLive(SenderId sender) {
        return repo.findLiveBySender(sender.value()).size();
    }

    /** The workspace's live assignment to the sender, as the workspace may see it. */
    public Optional<View> forTenant(Ids.TenantId tenant, SenderId sender) {
        return repo.findLive(sender.value(), tenant.value()).map(this::view);
    }

    /* ------------------------------- write -------------------------------- */

    /** Assign, or edit the label and cap of a live assignment (PUT semantics). */
    @Transactional
    public View assign(SenderId sender, Ids.TenantId tenant, String label, Integer dailyOutboundCap, String actor) {
        WhatsAppSender s = senders.requireForTenant(null, sender);
        if (s.status() != SenderStatus.ACTIVE && s.status() != SenderStatus.SUSPENDED) {
            throw AdminApiException.conflict("sender_not_assignable",
                    "only a live or paused number can serve workspaces — this one is " + s.status());
        }
        TenantEntity t = tenants.findById(tenant.value()).orElseThrow(() ->
                AdminApiException.badRequest("unknown_tenant", "no such workspace: " + tenant.value()));
        if (!TenantEntity.Status.ACTIVE.name().equals(t.getStatus())) {
            throw AdminApiException.badRequest("destination_not_active",
                    "workspace '" + tenant.value() + "' is " + t.getStatus().toLowerCase() + " — it can't use a number");
        }
        String cleanLabel = label == null ? "" : label.trim().replaceAll("\\s+", " ");
        if (cleanLabel.isEmpty() || cleanLabel.length() > MAX_LABEL_CHARS) {
            throw AdminApiException.badRequest("bad_label",
                    "label is the business name recipients see: 1–" + MAX_LABEL_CHARS + " characters");
        }
        int cap = dailyOutboundCap == null ? DEFAULT_DAILY_CAP : dailyOutboundCap;
        if (cap < 0) throw AdminApiException.badRequest("bad_cap", "dailyOutboundCap must be 0 or more");

        Optional<WhatsAppSenderAssignmentEntity> existing = repo.findLive(sender.value(), tenant.value());
        WhatsAppSenderAssignmentEntity row;
        if (existing.isPresent()) {
            row = existing.get();
            if (!row.getLabel().equals(cleanLabel)) {
                event(row, "LABEL_CHANGED", Map.of("from", row.getLabel(), "to", cleanLabel), actor);
                row.setLabel(cleanLabel);
            }
            if (row.getDailyOutboundCap() != cap) {
                event(row, "CAP_CHANGED", Map.of("from", row.getDailyOutboundCap(), "to", cap), actor);
                row.setDailyOutboundCap(cap);
            }
        } else {
            row = new WhatsAppSenderAssignmentEntity(
                    "wsa_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24),
                    sender.value(), tenant.value(), cleanLabel, cap, actor);
            event(row, "ASSIGNED", Map.of("label", cleanLabel, "dailyOutboundCap", cap), actor);
        }
        try {
            row = repo.saveAndFlush(row);
        } catch (DataIntegrityViolationException dup) {
            throw AdminApiException.conflict("label_taken",
                    "another workspace on this number already shows as '" + cleanLabel + "'");
        }
        log.info("Platform sender {} assigned to {} as '{}' (cap {}) by {}",
                sender.value(), tenant.value(), cleanLabel, cap, actor);
        return view(row);
    }

    @Transactional
    public View pause(SenderId sender, Ids.TenantId tenant, String actor) {
        return setStatus(sender, tenant, WhatsAppSenderAssignmentEntity.PAUSED, "PAUSED", actor);
    }

    @Transactional
    public View resume(SenderId sender, Ids.TenantId tenant, String actor) {
        return setStatus(sender, tenant, WhatsAppSenderAssignmentEntity.ACTIVE, "RESUMED", actor);
    }

    /** The workspace's own reply to people who aren't its staff (§8.3). */
    @Transactional
    public View setNonMemberReply(Ids.TenantId tenant, SenderId sender, String reply) {
        WhatsAppSenderAssignmentEntity row = requireLive(sender, tenant);
        String text = reply == null || reply.isBlank() ? null : reply.trim();
        if (text != null && text.length() > MAX_REPLY_CHARS) {
            throw AdminApiException.badRequest("reply_too_long", "the reply is at most " + MAX_REPLY_CHARS + " characters");
        }
        row.setNonMemberReply(text);
        return view(repo.save(row));
    }

    /**
     * Stop serving the workspace (§11): its links, bindings, keywords, staff,
     * open sessions, parked replies and pins on the number go; opt-outs stay
     * with the number.
     */
    @Transactional
    public EndOutcome end(SenderId sender, Ids.TenantId tenant, String actor, boolean dryRun) {
        WhatsAppSenderAssignmentEntity row = requireLive(sender, tenant);
        List<SystemSenderEntity> linked = systemSenders.findByTenantIdAndWhatsappSenderId(tenant.value(), sender.value());
        List<ChannelBindingEntity> live = bindings.findByTenantIdOrderByPriorityAscIdAsc(tenant.value()).stream()
                .filter(b -> ENDPOINT_KIND.equals(b.getEndpointKind()) && sender.value().equals(b.getEndpointId()))
                .filter(b -> !BindingStatus.DEACTIVATED.name().equals(b.getStatus()))
                .toList();
        List<OutboundPendingEntity> parkedRows =
                parked.findByTenantIdAndSenderIdAndReleasedAtIsNull(tenant.value(), sender.value());
        if (dryRun) {
            return new EndOutcome(true, new EndEffects(
                    linked.stream().map(SystemSenderEntity::getId).toList(),
                    live.size(),
                    keywords.list(tenant, sender).size(),
                    members.count(tenant, sender),
                    sessions.countOpenForSender(tenant, sender),
                    parkedRows.size(),
                    0));
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        linked.forEach(s -> s.setWhatsappSenderId(null));
        systemSenders.saveAll(linked);
        for (ChannelBindingEntity b : live) {
            b.setStatus(BindingStatus.DEACTIVATED.name());
            b.setDeactivatedAt(now);
        }
        bindings.saveAll(live);
        int keywordRows = keywords.removeAll(tenant, sender);
        long memberRows = members.removeAll(tenant, sender);
        int closed = sessions.closeAllOpenForSender(tenant, sender, "ASSIGNMENT_ENDED");
        parked.deleteAll(parkedRows);
        int unpinned = routes.clearActive(sender.value(), tenant.value());

        row.setStatus(WhatsAppSenderAssignmentEntity.ENDED);
        row.setEndedAt(now);
        row.setEndedBy(actor);
        repo.save(row);
        EndEffects effects = new EndEffects(linked.stream().map(SystemSenderEntity::getId).toList(), live.size(),
                keywordRows, memberRows, closed, parkedRows.size(), unpinned);
        event(row, "ENDED", Map.of("effects", effects), actor);
        log.info("Platform sender {} no longer serves {} (by {}): {}", sender.value(), tenant.value(), actor, effects);
        return new EndOutcome(false, effects);
    }

    /** An archived workspace stops using every platform sender it was assigned to. */
    @org.springframework.context.event.EventListener
    @Transactional
    public void onWorkspaceArchived(com.yayatechandinnovations.yayaagentic.operator_auth.workspace
                                            .WorkspaceArchivedEvent event) {
        for (WhatsAppSenderAssignmentEntity a : repo.findLiveByTenant(event.tenant().value())) {
            end(new SenderId(a.getSenderId()), event.tenant(), "archive:" + event.actor(), false);
        }
    }

    /* ------------------------------ helpers ------------------------------- */

    private View setStatus(SenderId sender, Ids.TenantId tenant, String status, String action, String actor) {
        WhatsAppSenderAssignmentEntity row = requireLive(sender, tenant);
        if (!status.equals(row.getStatus())) {
            row.setStatus(status);
            row = repo.save(row);
            event(row, action, Map.of(), actor);
        }
        return view(row);
    }

    private WhatsAppSenderAssignmentEntity requireLive(SenderId sender, Ids.TenantId tenant) {
        return repo.findLive(sender.value(), tenant.value()).orElseThrow(() ->
                AdminApiException.notFound("assignment_not_found",
                        "workspace '" + tenant.value() + "' is not assigned to this number"));
    }

    private View view(WhatsAppSenderAssignmentEntity a) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        long sends = deliveries
                .countByTenantIdAndWhatsappSenderIdAndTemplateNameIsNotNullAndFallbackReasonIsNullAndCreatedAtAfter(
                        a.getTenantId(), a.getSenderId(), now.minusHours(24));
        long stops = optOuts.countBySenderIdAndAttributedTenantIdAndOptedOutAtAfter(
                a.getSenderId(), a.getTenantId(), now.minusDays(7));
        return new View(a.getId(), a.getSenderId(), a.getTenantId(), a.getLabel(), a.getStatus(),
                a.getDailyOutboundCap(), a.getNonMemberReply(), a.getAssignedBy(),
                a.getAssignedAt() == null ? null : a.getAssignedAt().toInstant(),
                a.getEndedAt() == null ? null : a.getEndedAt().toInstant(),
                sends, stops, members.count(new Ids.TenantId(a.getTenantId()), new SenderId(a.getSenderId())));
    }

    private void event(WhatsAppSenderAssignmentEntity a, String action, Map<String, Object> detail, String actor) {
        String detailJson;
        try {
            detailJson = json.writeValueAsString(new LinkedHashMap<>(detail));
        } catch (JsonProcessingException e) {
            detailJson = "{}";
        }
        events.save(new WhatsAppSenderAssignmentEventEntity(a.getSenderId(), a.getTenantId(), action, detailJson,
                actor == null ? "unknown" : actor));
    }
}
