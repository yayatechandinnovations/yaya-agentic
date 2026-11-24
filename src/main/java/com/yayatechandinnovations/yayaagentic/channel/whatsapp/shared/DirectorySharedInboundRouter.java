package com.yayatechandinnovations.yayaagentic.channel.whatsapp.shared;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.DefaultInteractiveRenderer;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSender;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.keywords.KeywordActionService;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.keywords.KeywordTextNormalizer;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.InboundMessageEntity;
import com.yayatechandinnovations.yayaagentic.persistence.InboundMessageRepository;
import com.yayatechandinnovations.yayaagentic.persistence.OutboundMessageEntity;
import com.yayatechandinnovations.yayaagentic.persistence.OutboundMessageRepository;
import com.yayatechandinnovations.yayaagentic.persistence.OutreachActionRepository;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppSenderAssignmentEntity;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppSenderAssignmentRepository;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppSenderMemberEntity;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppSenderMemberRepository;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppSharedRouteEntity;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppSharedRouteRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The router over the member directory (whatsapp-shared-sender-design.md §7.2):
 * the phone lookup first, then what the message itself says, then the pin,
 * and only then a question.
 */
@Component
public class DirectorySharedInboundRouter implements SharedInboundRouter {

    /** How long the workspace a person last talked to stays theirs without asking. */
    public static final Duration PIN_TTL = Duration.ofMinutes(30);
    /** An unanswered «¿Con cuál negocio?» is dropped after this. */
    public static final Duration PICK_TTL = Duration.ofMinutes(30);
    /** A reply keyword answers a workspace that wrote to the phone this recently. */
    public static final Duration REPLY_WINDOW = Duration.ofDays(7);
    /** A non-member reply names the workspace that wrote to the phone this recently. */
    public static final Duration ATTRIBUTION_WINDOW = Duration.ofDays(30);

    /** Switch words, normalised (§7.2 signal 5). Reserved on platform senders. */
    public static final Set<String> SWITCH_WORDS = Set.of("CAMBIAR", "CAMBIAR NEGOCIO", "#NEGOCIO");

    private final WhatsAppSenderMemberRepository members;
    private final WhatsAppSenderAssignmentRepository assignments;
    private final WhatsAppSharedRouteRepository routes;
    private final OutreachActionRepository actions;
    private final OutboundMessageRepository outbound;
    private final InboundMessageRepository inbound;
    private final KeywordActionService keywords;
    private final ObjectMapper json;
    private final Clock clock;

    public DirectorySharedInboundRouter(WhatsAppSenderMemberRepository members,
                                        WhatsAppSenderAssignmentRepository assignments,
                                        WhatsAppSharedRouteRepository routes,
                                        OutreachActionRepository actions,
                                        OutboundMessageRepository outbound,
                                        InboundMessageRepository inbound,
                                        KeywordActionService keywords,
                                        ObjectMapper json,
                                        Clock clock) {
        this.members = members;
        this.assignments = assignments;
        this.routes = routes;
        this.actions = actions;
        this.outbound = outbound;
        this.inbound = inbound;
        this.keywords = keywords;
        this.json = json;
        this.clock = clock;
    }

    @Override
    @Transactional
    public RouteDecision route(WhatsAppSender sender, InboundMessageEntity row, Map<String, Object> raw) {
        String senderId = sender.id().value();
        String phone = row.getEndUserPhone();
        OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        String body = text(raw.get("Body"));
        String normalized = KeywordTextNormalizer.normalize(body);

        // Only ACTIVE assignments take inbound: a paused workspace could not answer.
        Map<String, WhatsAppSenderAssignmentEntity> live = new LinkedHashMap<>();
        for (WhatsAppSenderAssignmentEntity a : assignments.findLiveBySender(senderId)) {
            if (WhatsAppSenderAssignmentEntity.ACTIVE.equals(a.getStatus())) live.put(a.getTenantId(), a);
        }

        // Step A — the phone lookup. Candidates keep assignment order, so the
        // question always lists the businesses the same way.
        Set<String> memberOf = new java.util.HashSet<>();
        for (WhatsAppSenderMemberEntity m : members.findBySenderIdAndPhoneE164(senderId, phone)) {
            memberOf.add(m.getTenantId());
        }
        List<String> candidates = new ArrayList<>();
        for (String t : live.keySet()) if (memberOf.contains(t)) candidates.add(t);

        WhatsAppSharedRouteEntity route = routes.find(senderId, phone)
                .orElseGet(() -> new WhatsAppSharedRouteEntity(senderId, phone));
        String previousPin = pinned(route, now, PIN_TTL);

        // Step B — what the message itself says.
        // 1. The answer to a pending pick.
        Optional<PendingChoice> pending = pendingChoice(route, now);
        if (pending.isPresent()) {
            Optional<WhatsAppSenderAssignmentEntity> picked = pickedFrom(pending.get(), raw, body, live);
            if (picked.isPresent()) {
                String tenant = picked.get().getTenantId();
                route.setPendingChoice(null);
                pin(route, tenant, now);
                return routed(tenant, "pick", candidates, true, Optional.ofNullable(pending.get().heldMessageSid()),
                        live);
            }
        }
        // 2. A bound action button names the workspace that sent it.
        String payload = buttonPayload(raw);
        if (payload != null && payload.startsWith(DefaultInteractiveRenderer.ACTION_PREFIX)) {
            String actionId = payload.substring(DefaultInteractiveRenderer.ACTION_PREFIX.length());
            Optional<String> owner = actions.findById(actionId)
                    .map(a -> a.getTenantId())
                    .filter(live::containsKey);
            if (owner.isPresent()) return routedBySignal(route, owner.get(), "action", candidates, previousPin, now, live);
        }
        // 3. A quoted reply (also every button tap on a message) names the workspace that sent that message.
        String replied = text(raw.get("OriginalRepliedMessageSid"));
        if (!replied.isBlank()) {
            Optional<String> owner = outbound.findById(replied)
                    .filter(o -> senderId.equals(o.getSenderId()))
                    .map(OutboundMessageEntity::getTenantId)
                    .filter(live::containsKey);
            if (owner.isPresent()) return routedBySignal(route, owner.get(), "quoted_reply", candidates, previousPin, now, live);
        }
        // 4. A ROUTED keyword is unique on the number: it names its workspace.
        Optional<KeywordActionService.Owned> keyword = keywords.matchRouted(sender.id(), body);
        if (keyword.isPresent() && live.containsKey(keyword.get().tenant().value())) {
            String tenant = keyword.get().tenant().value();
            return new RouteDecision.Routed(new Ids.TenantId(tenant), "routed_keyword", candidates.contains(tenant),
                    false, Optional.empty(), live.get(tenant).getLabel());
        }
        // 5. The person asks to switch.
        if (SWITCH_WORDS.contains(normalized)) {
            if (candidates.size() >= 2) return ask(route, candidates, null, now, live);
            if (candidates.size() == 1) {
                pin(route, candidates.get(0), now);
                return routed(candidates.get(0), "switch", candidates, true, Optional.empty(), live);
            }
            return new RouteDecision.NotRouted(attributed(senderId, phone, now, live));
        }

        // Step C — nothing in the message names a workspace.
        if (candidates.isEmpty()) {
            List<KeywordActionService.Owned> replyOwners = keywords.matchReply(sender.id(), body).stream()
                    .filter(o -> live.containsKey(o.tenant().value()))
                    .toList();
            if (!replyOwners.isEmpty()) {
                Set<String> wrote = wroteSince(senderId, phone, now.minus(REPLY_WINDOW), live);
                List<String> owners = replyOwners.stream().map(o -> o.tenant().value()).filter(wrote::contains).toList();
                if (owners.size() == 1) {
                    return new RouteDecision.Routed(new Ids.TenantId(owners.get(0)), "reply_keyword", false, false,
                            Optional.empty(), live.get(owners.get(0)).getLabel());
                }
                if (owners.size() > 1) {
                    if (previousPin != null && owners.contains(previousPin)) {
                        return new RouteDecision.Routed(new Ids.TenantId(previousPin), "reply_keyword", false, false,
                                Optional.empty(), live.get(previousPin).getLabel());
                    }
                    return ask(route, owners, row.getMessageSid(), now, live);
                }
            }
            return new RouteDecision.NotRouted(attributed(senderId, phone, now, live));
        }
        if (candidates.size() == 1) {
            pin(route, candidates.get(0), now);
            return routed(candidates.get(0), "member", candidates, false, Optional.empty(), live);
        }

        OffsetDateTime lastInbound = inbound
                .findFirstBySenderIdAndEndUserPhoneAndReceivedAtBeforeOrderByReceivedAtDesc(
                        senderId, phone, row.getReceivedAt() == null ? now : row.getReceivedAt())
                .map(InboundMessageEntity::getReceivedAt)
                .orElse(null);
        List<String> wroteSinceLast = orderedWriters(senderId, phone,
                lastInbound == null ? now.minus(ATTRIBUTION_WINDOW) : lastInbound, live).stream()
                .filter(candidates::contains)
                .toList();
        if (previousPin != null && candidates.contains(previousPin)
                && wroteSinceLast.stream().allMatch(previousPin::equals)) {
            pin(route, previousPin, now);
            return routed(previousPin, "pinned", candidates, false, Optional.empty(), live);
        }
        if (wroteSinceLast.size() == 1) {
            String tenant = wroteSinceLast.get(0);
            boolean switched = !tenant.equals(previousPin);
            pin(route, tenant, now);
            return routed(tenant, "last_writer", candidates, switched, Optional.empty(), live);
        }
        // Most recent writer first, then the rest in assignment order.
        List<String> order = new ArrayList<>(new LinkedHashSet<>(wroteSinceLast));
        for (String c : candidates) if (!order.contains(c)) order.add(c);
        return ask(route, order, row.getMessageSid(), now, live);
    }

    /* ------------------------------------------------------------------ */

    /** A signal named the workspace; a member's pin follows it, so the next free text goes there too. */
    private RouteDecision routedBySignal(WhatsAppSharedRouteEntity route, String tenant, String reason,
                                         List<String> candidates, String previousPin, OffsetDateTime now,
                                         Map<String, WhatsAppSenderAssignmentEntity> live) {
        boolean member = candidates.contains(tenant);
        if (member) pin(route, tenant, now);
        boolean announce = member && candidates.size() >= 2 && !tenant.equals(previousPin);
        return new RouteDecision.Routed(new Ids.TenantId(tenant), reason, member, announce, Optional.empty(),
                live.get(tenant).getLabel());
    }

    private RouteDecision.Routed routed(String tenant, String reason, List<String> candidates, boolean switched,
                                        Optional<String> replay,
                                        Map<String, WhatsAppSenderAssignmentEntity> live) {
        boolean member = candidates.contains(tenant);
        boolean announce = member && candidates.size() >= 2 && switched;
        return new RouteDecision.Routed(new Ids.TenantId(tenant), reason, member, announce, replay,
                live.get(tenant).getLabel());
    }

    private RouteDecision ask(WhatsAppSharedRouteEntity route, List<String> tenants, String heldMessageSid,
                              OffsetDateTime now, Map<String, WhatsAppSenderAssignmentEntity> live) {
        List<Candidate> out = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (String t : tenants) {
            WhatsAppSenderAssignmentEntity a = live.get(t);
            if (a == null) continue;
            out.add(new Candidate(a.getId(), new Ids.TenantId(t), a.getLabel()));
            ids.add(a.getId());
        }
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("assignmentIds", ids);
        pending.put("heldMessageSid", heldMessageSid);
        pending.put("askedAt", now.toString());
        route.setPendingChoice(write(pending));
        routes.save(route);
        return new RouteDecision.Ask(out, heldMessageSid != null);
    }

    private void pin(WhatsAppSharedRouteEntity route, String tenant, OffsetDateTime now) {
        route.setActiveTenantId(tenant);
        route.setActiveAt(now);
        routes.save(route);
    }

    private static String pinned(WhatsAppSharedRouteEntity route, OffsetDateTime now, Duration ttl) {
        if (route.getActiveTenantId() == null || route.getActiveAt() == null) return null;
        return route.getActiveAt().isAfter(now.minus(ttl)) ? route.getActiveTenantId() : null;
    }

    record PendingChoice(List<String> assignmentIds, String heldMessageSid) {}

    private Optional<PendingChoice> pendingChoice(WhatsAppSharedRouteEntity route, OffsetDateTime now) {
        if (route.getPendingChoice() == null || route.getPendingChoice().isBlank()) return Optional.empty();
        try {
            Map<String, Object> m = json.readValue(route.getPendingChoice(), new TypeReference<Map<String, Object>>() {});
            OffsetDateTime asked = OffsetDateTime.parse(String.valueOf(m.get("askedAt")));
            if (asked.isBefore(now.minus(PICK_TTL))) return Optional.empty();
            @SuppressWarnings("unchecked")
            List<String> ids = m.get("assignmentIds") instanceof List<?> l ? (List<String>) l : List.of();
            Object held = m.get("heldMessageSid");
            return Optional.of(new PendingChoice(ids, held == null ? null : String.valueOf(held)));
        } catch (JsonProcessingException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * The tapped button, the list row, the label typed back, or the row's
     * number — whatever WhatsApp rendered the question as (buttons, a list or
     * numbered text), the answer maps back to one assignment.
     */
    private static Optional<WhatsAppSenderAssignmentEntity> pickedFrom(
            PendingChoice pending, Map<String, Object> raw, String body,
            Map<String, WhatsAppSenderAssignmentEntity> live) {
        List<WhatsAppSenderAssignmentEntity> offered = new ArrayList<>();
        for (String id : pending.assignmentIds()) {
            live.values().stream().filter(a -> a.getId().equals(id)).findFirst().ifPresent(offered::add);
        }
        if (offered.isEmpty()) return Optional.empty();
        List<String> answers = new ArrayList<>();
        for (String key : List.of("ButtonText", "ListTitle", "ButtonPayload", "ListId")) {
            String v = text(raw.get(key));
            if (!v.isBlank()) answers.add(v);
        }
        answers.add(body);
        for (String answer : answers) {
            String a = answer.trim();
            for (WhatsAppSenderAssignmentEntity candidate : offered) {
                if (a.contains(candidate.getId())) return Optional.of(candidate);
                if (a.equalsIgnoreCase(candidate.getLabel())
                        || KeywordTextNormalizer.normalize(a).equals(KeywordTextNormalizer.normalize(candidate.getLabel()))) {
                    return Optional.of(candidate);
                }
            }
            if (a.matches("\\d{1,2}")) {
                int n = Integer.parseInt(a);
                if (n >= 1 && n <= offered.size()) return Optional.of(offered.get(n - 1));
            }
        }
        return Optional.empty();
    }

    /** The one workspace that wrote to the phone in the attribution window, if only one did. */
    private Optional<Ids.TenantId> attributed(String senderId, String phone, OffsetDateTime now,
                                              Map<String, WhatsAppSenderAssignmentEntity> live) {
        Set<String> wrote = wroteSince(senderId, phone, now.minus(ATTRIBUTION_WINDOW), live);
        return wrote.size() == 1 ? Optional.of(new Ids.TenantId(wrote.iterator().next())) : Optional.empty();
    }

    private Set<String> wroteSince(String senderId, String phone, OffsetDateTime since,
                                   Map<String, WhatsAppSenderAssignmentEntity> live) {
        return new LinkedHashSet<>(orderedWriters(senderId, phone, since, live));
    }

    /** Workspaces that sent to the phone since {@code since}, most recent first. */
    private List<String> orderedWriters(String senderId, String phone, OffsetDateTime since,
                                        Map<String, WhatsAppSenderAssignmentEntity> live) {
        List<String> out = new ArrayList<>();
        for (OutboundMessageEntity o
                : outbound.findTop50BySenderIdAndEndUserPhoneAndSentAtAfterOrderBySentAtDesc(senderId, phone, since)) {
            String t = o.getTenantId();
            if (t != null && live.containsKey(t) && !out.contains(t)) out.add(t);
        }
        return out;
    }

    private static String buttonPayload(Map<String, Object> raw) {
        String p = text(raw.get("ButtonPayload"));
        return p.isBlank() ? null : p.trim();
    }

    private static String text(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not encode the pending choice", e);
        }
    }
}
