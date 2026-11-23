package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.api.AdminApiException;
import com.yayatechandinnovations.yayaagentic.channel.ChannelEndpointRef;
import com.yayatechandinnovations.yayaagentic.channel.binding.BindingStatus;
import com.yayatechandinnovations.yayaagentic.channel.binding.ChannelBindingService;
import com.yayatechandinnovations.yayaagentic.channel.binding.RoutingRule;
import com.yayatechandinnovations.yayaagentic.channel.binding.SessionPolicy;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.ChannelBindingEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChannelBindingRepository;
import com.yayatechandinnovations.yayaagentic.persistence.ChannelOptOutEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChannelOptOutRepository;
import com.yayatechandinnovations.yayaagentic.persistence.OutboundPendingEntity;
import com.yayatechandinnovations.yayaagentic.persistence.OutboundPendingRepository;
import com.yayatechandinnovations.yayaagentic.persistence.ProfileEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ProfileRepository;
import com.yayatechandinnovations.yayaagentic.persistence.SystemSenderEntity;
import com.yayatechandinnovations.yayaagentic.persistence.SystemSenderRepository;
import com.yayatechandinnovations.yayaagentic.persistence.TenantEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantRepository;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppSenderEntity;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppSenderRepository;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppSenderTransferEntity;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppSenderTransferEntity.RepointStatus;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppSenderTransferRepository;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppTemplateEntity;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppTemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Moves a live WhatsApp sender to another workspace without a new
 * registration (whatsapp-sender-transfer-design.md). The source row is
 * retired and a new row is created in the destination for the same BSP
 * sender, in one transaction; the BSP's webhooks are re-pointed after commit
 * and retried until they land. Authorization (platform admins only, §5) is the
 * controller's.
 */
@Service
public class WhatsAppSenderTransferService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppSenderTransferService.class);
    private static final String ENDPOINT_KIND = "WHATSAPP_SENDER";
    private static final String PLATFORM_ACCOUNT = "platform";
    /** After this long a re-point stops retrying and waits for a platform admin (§6.4). */
    static final Duration REPOINT_GIVE_UP = Duration.ofHours(24);

    private final WhatsAppSenderService senders;
    private final WhatsAppSenderRepository senderRepo;
    private final WhatsAppSenderTransferRepository transfers;
    private final TenantRepository tenants;
    private final ChannelSessionService channelSessions;
    private final OutboundPendingRepository parkedOutbound;
    private final ChannelBindingRepository bindingRepo;
    private final ChannelBindingService bindings;
    private final SystemSenderRepository systemSenders;
    private final ChannelOptOutRepository optOuts;
    private final WhatsAppTemplateRepository templates;
    private final ProfileRepository profiles;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public WhatsAppSenderTransferService(WhatsAppSenderService senders,
                                         WhatsAppSenderRepository senderRepo,
                                         WhatsAppSenderTransferRepository transfers,
                                         TenantRepository tenants,
                                         ChannelSessionService channelSessions,
                                         OutboundPendingRepository parkedOutbound,
                                         ChannelBindingRepository bindingRepo,
                                         ChannelBindingService bindings,
                                         SystemSenderRepository systemSenders,
                                         ChannelOptOutRepository optOuts,
                                         WhatsAppTemplateRepository templates,
                                         ProfileRepository profiles,
                                         ObjectMapper json,
                                         PlatformTransactionManager txManager) {
        this.senders = senders;
        this.senderRepo = senderRepo;
        this.transfers = transfers;
        this.tenants = tenants;
        this.channelSessions = channelSessions;
        this.parkedOutbound = parkedOutbound;
        this.bindingRepo = bindingRepo;
        this.bindings = bindings;
        this.systemSenders = systemSenders;
        this.optOuts = optOuts;
        this.templates = templates;
        this.profiles = profiles;
        this.json = json;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * @param profileId      the destination assistant to route the number to; null leaves it unrouted
     * @param profileVersion pins the route to a version; null follows the latest
     * @param copyOptOuts    null means true — nobody who opted out of this number hears from it again
     */
    public record TransferRequest(String destinationTenant, String profileId, Integer profileVersion,
                                  Boolean copyOptOuts) {}

    public record Effects(int optOutsCopied,
                          int openSessionsClosed,
                          int parkedOutboundDropped,
                          int bindingsDeactivated,
                          List<String> systemSendersUnlinked,
                          List<String> templatesNotHeldByDestination) {}

    /**
     * @param destination    absent on a dry run
     * @param webhookRepoint {@code DONE} or {@code PENDING}; absent on a dry run
     */
    public record Outcome(String transferId,
                          WhatsAppSender source,
                          Optional<WhatsAppSender> destination,
                          Optional<String> webhookRepoint,
                          Effects effects,
                          List<String> warnings) {}

    /* ---------------------------- transfer -------------------------------- */

    public Outcome transfer(Ids.TenantId source, SenderId senderId, TransferRequest req,
                            String requestedBy, boolean dryRun) {
        if (dryRun) {
            WhatsAppSenderEntity row = validate(source, senderId, req);
            WhatsAppSender sender = senders.toDomain(row);
            Effects effects = new Effects(
                    copyOptOuts(req) ? optOuts.findBySenderId(row.getId()).size() : 0,
                    channelSessions.countOpenForSender(source, senderId),
                    parkedOutbound.findByTenantIdAndSenderIdAndReleasedAtIsNull(source.value(), row.getId()).size(),
                    liveBindings(source, row.getId()).size(),
                    linkedSystemSenders(source, row.getId()).stream().map(SystemSenderEntity::getId).toList(),
                    templatesNotHeld(source.value(), req.destinationTenant()));
            return new Outcome(null, sender, Optional.empty(), Optional.empty(), effects, warnings(req, effects));
        }

        Applied applied;
        try {
            applied = tx.execute(status -> apply(source, senderId, req, requestedBy));
        } catch (DataIntegrityViolationException race) {
            throw AdminApiException.conflict("transfer_conflict",
                    "this sender changed while it was being moved — reload and try again");
        }
        String repoint = attemptRepoint(applied.transferId());
        return new Outcome(applied.transferId(),
                senders.requireForTenant(source, senderId),
                Optional.of(senders.requireForTenant(applied.destTenant(), applied.destSenderId())),
                Optional.of(repoint),
                applied.effects(),
                warnings(req, applied.effects()));
    }

    private record Applied(String transferId, Ids.TenantId destTenant, SenderId destSenderId, Effects effects) {}

    private Applied apply(Ids.TenantId source, SenderId senderId, TransferRequest req, String requestedBy) {
        WhatsAppSenderEntity row = validate(source, senderId, req);
        Ids.TenantId dest = new Ids.TenantId(req.destinationTenant());
        String transferId = "wst_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        int sessionsClosed = channelSessions.closeAllOpenForSender(source, senderId, "SENDER_TRANSFERRED");

        List<OutboundPendingEntity> parked =
                parkedOutbound.findByTenantIdAndSenderIdAndReleasedAtIsNull(source.value(), row.getId());
        parkedOutbound.deleteAll(parked);

        List<ChannelBindingEntity> sourceBindings = liveBindings(source, row.getId());
        for (ChannelBindingEntity b : sourceBindings) {
            b.setStatus(BindingStatus.DEACTIVATED.name());
            b.setDeactivatedAt(now);
        }
        bindingRepo.saveAll(sourceBindings);

        List<SystemSenderEntity> linked = linkedSystemSenders(source, row.getId());
        linked.forEach(s -> s.setWhatsappSenderId(null));
        systemSenders.saveAll(linked);

        List<String> templatesNotHeld = templatesNotHeld(source.value(), dest.value());

        // The partial unique indexes (V92) allow one live row per phone and BSP
        // sid: the source must be retired and flushed before the copy inserts,
        // and Hibernate would otherwise order the insert first.
        SenderStatus liveStatus = SenderStatus.valueOf(row.getStatus());
        row.setStatus(SenderStatus.REVOKED.name());
        row.setUpdatedAt(now);
        senderRepo.saveAndFlush(row);

        Map<String, Object> transferMeta = new LinkedHashMap<>();
        transferMeta.put("transferId", transferId);
        transferMeta.put("fromTenant", source.value());
        transferMeta.put("fromSenderId", row.getId());
        transferMeta.put("at", now.toString());
        transferMeta.put("webhooks", RepointStatus.PENDING.name());
        WhatsAppSenderEntity copy = senders.insertTransferredCopy(row, liveStatus, dest, transferMeta);

        row.setTransferredToSenderId(copy.getId());
        senderRepo.save(row);

        int optOutsCopied = 0;
        if (copyOptOuts(req)) {
            List<ChannelOptOutEntity> copied = optOuts.findBySenderId(row.getId()).stream()
                    .map(o -> new ChannelOptOutEntity(copy.getId(), o.getEndUserPhone(), o.getReason()))
                    .toList();
            optOuts.saveAll(copied);
            optOutsCopied = copied.size();
        }

        if (req.profileId() != null && !req.profileId().isBlank()) {
            int version = req.profileVersion() != null ? req.profileVersion() : latestVersion(dest, req.profileId());
            bindings.create(dest,
                    new ChannelEndpointRef.WhatsAppSenderRef(new SenderId(copy.getId())),
                    new Ids.ProfileId(req.profileId(), version),
                    Optional.ofNullable(req.profileVersion()),
                    new RoutingRule.MatchAny(),
                    SessionPolicy.defaults(),
                    100, null, null, Set.of(), null);
        }

        Effects effects = new Effects(optOutsCopied, sessionsClosed, parked.size(), sourceBindings.size(),
                linked.stream().map(SystemSenderEntity::getId).toList(), templatesNotHeld);
        transfers.save(new WhatsAppSenderTransferEntity(transferId, row.getPhoneE164(), row.getTwilioSenderSid(),
                source.value(), row.getId(), dest.value(), copy.getId(), writeJson(effects), requestedBy));

        log.info("WhatsApp sender {} ({}) transferred {} → {} as {} by {}",
                row.getId(), maskPhone(row.getPhoneE164()), source.value(), dest.value(), copy.getId(), requestedBy);
        return new Applied(transferId, dest, new SenderId(copy.getId()), effects);
    }

    /* ---------------------------- re-point -------------------------------- */

    /** Retry every re-point that hasn't landed — driven by {@link SenderSyncJob}. */
    public void retryPendingRepoints() {
        for (WhatsAppSenderTransferEntity t
                : transfers.findByRepointStatusOrderByCreatedAtAsc(RepointStatus.PENDING.name())) {
            try {
                attemptRepoint(t.getId());
            } catch (RuntimeException e) {
                log.warn("WhatsApp transfer {}: re-point retry failed: {}", t.getId(), e.getMessage());
            }
        }
    }

    /** A platform admin's retry, also after the automatic retries gave up. */
    public WhatsAppSender retryRepoint(Ids.TenantId destTenant, SenderId destSenderId) {
        WhatsAppSender sender = senders.requireForTenant(destTenant, destSenderId);
        WhatsAppSenderTransferEntity t = transfers.findFirstByDestSenderIdOrderByCreatedAtDesc(destSenderId.value())
                .filter(found -> found.getDestTenantId().equals(destTenant.value()))
                .orElseThrow(() -> AdminApiException.notFound("transfer_not_found",
                        "this sender was not transferred from another workspace"));
        if (RepointStatus.DONE.name().equals(t.getRepointStatus())) return sender;
        if (sender.status() == SenderStatus.REVOKED) {
            throw AdminApiException.conflict("sender_not_transferable",
                    "this sender has been removed — there is nothing to re-point");
        }
        if (!RepointStatus.DONE.name().equals(attemptRepoint(t.getId(), true))) {
            throw new AdminApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "repoint_failed",
                    "WhatsApp could not be reached to finish moving this number — try again in a minute. "
                            + "Messages to it still arrive here meanwhile.");
        }
        return senders.requireForTenant(destTenant, destSenderId);
    }

    private String attemptRepoint(String transferId) {
        return attemptRepoint(transferId, false);
    }

    /** Tries once; on failure the row stays PENDING (or turns FAILED past the give-up age). */
    private String attemptRepoint(String transferId, boolean manual) {
        WhatsAppSenderTransferEntity t = transfers.findById(transferId).orElseThrow();
        Ids.TenantId destTenant = new Ids.TenantId(t.getDestTenantId());
        SenderId destSender = new SenderId(t.getDestSenderId());
        String error = null;
        try {
            senders.repointWebhooks(destTenant, destSender);
        } catch (RuntimeException e) {
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("WhatsApp transfer {}: webhook re-point failed (inbound still forwards): {}",
                    transferId, error);
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        t.setRepointAttempts(t.getRepointAttempts() + 1);
        if (error == null) {
            t.setRepointStatus(RepointStatus.DONE.name());
            t.setRepointedAt(now);
            t.setRepointLastError(null);
        } else {
            t.setRepointLastError(error.length() > 2000 ? error.substring(0, 2000) : error);
            boolean givenUp = t.getCreatedAt() != null
                    && t.getCreatedAt().plus(REPOINT_GIVE_UP).isBefore(now);
            t.setRepointStatus(givenUp && !manual ? RepointStatus.FAILED.name() : RepointStatus.PENDING.name());
        }
        transfers.save(t);
        senders.recordRepointStatus(destTenant, destSender, t.getRepointStatus());
        return t.getRepointStatus();
    }

    /* ---------------------------- validation ------------------------------ */

    private WhatsAppSenderEntity validate(Ids.TenantId source, SenderId senderId, TransferRequest req) {
        if (req == null || req.destinationTenant() == null || req.destinationTenant().isBlank()) {
            throw AdminApiException.badRequest("missing_destination_tenant", "destinationTenant is required");
        }
        String dest = req.destinationTenant().trim();
        if (dest.equals(source.value())) {
            throw AdminApiException.badRequest("same_workspace",
                    "the sender is already in this workspace — choose another one");
        }
        TenantEntity destTenant = tenants.findById(dest).orElseThrow(() ->
                AdminApiException.badRequest("unknown_tenant", "no such workspace: " + dest));
        if (!TenantEntity.Status.ACTIVE.name().equals(destTenant.getStatus())) {
            throw AdminApiException.conflict("destination_not_active",
                    "workspace '" + dest + "' is " + destTenant.getStatus().toLowerCase()
                            + " — it can't take a WhatsApp number");
        }

        WhatsAppSenderEntity row = senderRepo.findByTenantIdAndId(source.value(), senderId.value())
                .orElseThrow(() -> AdminApiException.notFound("sender_not_found",
                        "no such WhatsApp sender '" + senderId.value() + "' in tenant '" + source.value() + "'"));
        SenderStatus status = SenderStatus.valueOf(row.getStatus());
        if ((status != SenderStatus.ACTIVE && status != SenderStatus.SUSPENDED) || row.getTwilioSenderSid() == null) {
            throw AdminApiException.conflict("sender_not_transferable",
                    "only a live or paused sender can move — a sender in " + status
                            + " has no WhatsApp registration to keep. Remove it and register the number "
                            + "in the other workspace.");
        }
        if (row.getMessagingAccountId() != null) {
            throw AdminApiException.conflict("transfer_unsupported_account",
                    "this number was connected with the business's own WhatsApp account, which belongs to "
                            + "this workspace — it can't move on its own");
        }
        if (row.getPhoneE164().startsWith("+155501")) {
            throw AdminApiException.badRequest("reserved_phone",
                    "the simulated playground sender can't move between workspaces");
        }

        if (req.profileId() != null && !req.profileId().isBlank()) {
            boolean exists = req.profileVersion() != null
                    ? profiles.findByTenantIdAndIdAndVersion(dest, req.profileId(), req.profileVersion()).isPresent()
                    : !profiles.findByTenantIdAndIdOrderByVersionDesc(dest, req.profileId()).isEmpty();
            if (!exists) {
                throw AdminApiException.badRequest("unknown_profile",
                        "no such assistant '" + req.profileId()
                                + (req.profileVersion() == null ? "" : "@" + req.profileVersion())
                                + "' in workspace '" + dest + "'");
            }
        }
        return row;
    }

    /* ---------------------------- helpers --------------------------------- */

    private List<ChannelBindingEntity> liveBindings(Ids.TenantId tenant, String senderId) {
        return bindingRepo.findByTenantIdOrderByPriorityAscIdAsc(tenant.value()).stream()
                .filter(b -> ENDPOINT_KIND.equals(b.getEndpointKind()) && senderId.equals(b.getEndpointId()))
                .filter(b -> !BindingStatus.DEACTIVATED.name().equals(b.getStatus()))
                .toList();
    }

    private List<SystemSenderEntity> linkedSystemSenders(Ids.TenantId tenant, String senderId) {
        return systemSenders.findByTenantIdAndWhatsappSenderId(tenant.value(), senderId);
    }

    /** Only platform-account senders transfer, so the account is always the platform's. */
    private List<String> templatesNotHeld(String source, String dest) {
        Set<Long> destHeld = new java.util.HashSet<>();
        templates.findHeld(PLATFORM_ACCOUNT, dest).forEach(t -> destHeld.add(t.getId()));
        List<String> missing = new ArrayList<>();
        for (WhatsAppTemplateEntity t : templates.findHeld(PLATFORM_ACCOUNT, source)) {
            if (!destHeld.contains(t.getId())) missing.add(t.getName() + " (" + t.getLocale() + ")");
        }
        return missing;
    }

    private int latestVersion(Ids.TenantId tenant, String profileId) {
        return profiles.findByTenantIdAndIdOrderByVersionDesc(tenant.value(), profileId).stream()
                .findFirst().map(ProfileEntity::getVersion)
                .orElseThrow(() -> AdminApiException.badRequest("unknown_profile",
                        "no such assistant '" + profileId + "' in workspace '" + tenant.value() + "'"));
    }

    private static boolean copyOptOuts(TransferRequest req) {
        return req.copyOptOuts() == null || req.copyOptOuts();
    }

    private static List<String> warnings(TransferRequest req, Effects effects) {
        List<String> warnings = new ArrayList<>();
        if (req.profileId() == null || req.profileId().isBlank()) {
            warnings.add("The destination has no route for this number yet — add one, or incoming "
                    + "messages get the unresolved reply.");
        }
        if (!effects.templatesNotHeldByDestination().isEmpty()) {
            warnings.add("The destination doesn't hold " + effects.templatesNotHeldByDestination().size()
                    + " template(s) this number used — share them from WhatsApp templates.");
        }
        if (!effects.systemSendersUnlinked().isEmpty()) {
            warnings.add("Integrations in the source workspace lose their WhatsApp number: "
                    + String.join(", ", effects.systemSendersUnlinked()) + ".");
        }
        return warnings;
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not encode transfer effects", e);
        }
    }

    private static String maskPhone(String phone) {
        return phone == null || phone.length() < 4 ? "****" : "****" + phone.substring(phone.length() - 4);
    }
}
