package com.yayatechandinnovations.yayaagentic.outreach;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.channel.system.MediationMode;
import com.yayatechandinnovations.yayaagentic.channel.system.OutreachRecipient;
import com.yayatechandinnovations.yayaagentic.channel.system.SystemInboundMessage;
import com.yayatechandinnovations.yayaagentic.channel.system.SystemSender;
import com.yayatechandinnovations.yayaagentic.channel.system.SystemSenderService;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.IntentFrame;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.core.Session;
import com.yayatechandinnovations.yayaagentic.core.Turn;
import com.yayatechandinnovations.yayaagentic.engine.ConversationEngine;
import com.yayatechandinnovations.yayaagentic.engine.StartSessionResult;
import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.persistence.ChatEntryPointEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChatEntryPointRepository;
import com.yayatechandinnovations.yayaagentic.persistence.ChatInboxEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChatInboxRepository;
import com.yayatechandinnovations.yayaagentic.persistence.OutreachSessionEntity;
import com.yayatechandinnovations.yayaagentic.persistence.OutreachSessionRepository;
import com.yayatechandinnovations.yayaagentic.persistence.SystemInboundMessageEntity;
import com.yayatechandinnovations.yayaagentic.persistence.SystemInboundMessageRepository;
import com.yayatechandinnovations.yayaagentic.profile.StartConversationRequest;
import com.yayatechandinnovations.yayaagentic.recorder.ConversationRecorder;
import com.yayatechandinnovations.yayaagentic.recorder.RecorderContext;
import com.yayatechandinnovations.yayaagentic.recorder.RecorderRouter;
import com.yayatechandinnovations.yayaagentic.recorder.TurnRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Reference {@link OutreachService}. Orchestrates SM-0's ingest → verbatim chat
 * delivery (§5–§7, §11, §12):
 *
 * <ol>
 *   <li>validate the sender is enabled and permitted to address the recipient;</li>
 *   <li>claim idempotency by inserting the ingest-ledger row (unique dedup key);</li>
 *   <li>resolve the recipient to delivery targets — empty ⇒ silent drop (§6);</li>
 *   <li>find-or-create the recipient's durable outreach session (minted via
 *       {@code engine.start} with a synthetic principal that OWNS the session, §12);</li>
 *   <li>record the injected message as a {@code Turn} (origin {@code
 *       SYSTEM_PARTICIPANT}) through the {@code ConversationRecorder} — the engine
 *       boundary is never bypassed (non-negotiable #4);</li>
 *   <li>write the {@code chat_inbox} row so the message exists the moment ingest
 *       returns, even if the operator is offline (§11).</li>
 * </ol>
 *
 * <p>The injected content is data, not instruction: SM-0 delivers verbatim and
 * the recorded Turn is non-executable (no tool call can originate from it). LLM
 * mediation + the reply loop land in SM-2 (§7.2, §8, §9).
 */
@Service
public class DefaultOutreachService implements OutreachService {

    private static final Logger log = LoggerFactory.getLogger(DefaultOutreachService.class);

    static final String ORIGIN_SYSTEM_PARTICIPANT = "SYSTEM_PARTICIPANT";

    private final SystemSenderService senders;
    private final List<RecipientResolver> resolvers;
    private final ConversationEngine engine;
    private final RecorderRouter recorderRouter;
    private final ChatEntryPointRepository entryPoints;
    private final OutreachSessionRepository outreachSessions;
    private final ChatInboxRepository chatInbox;
    private final SystemInboundMessageRepository ledger;
    private final MediationFramer framer;
    private final MediationService mediation;
    private final com.yayatechandinnovations.yayaagentic.outreach.media.MediaLibraryService mediaLibrary;
    private final com.yayatechandinnovations.yayaagentic.outreach.push.PushNotifier pushNotifier;
    private final ObjectMapper json;
    // Optional collaborators (whatsapp-ops-integration-design.md §6): field
    // injection keeps the hand-built test constructors compiling; without them
    // delivery is chat-only and action buttons stay plain text.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private OutreachActionService actionService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppOutreachLeg whatsappLeg;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.yayatechandinnovations.yayaagentic.persistence.OutreachDeliveryRepository deliveries;

    void setActionService(OutreachActionService actionService) { this.actionService = actionService; }

    void setWhatsappLeg(com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppOutreachLeg leg) {
        this.whatsappLeg = leg;
    }

    void setDeliveries(com.yayatechandinnovations.yayaagentic.persistence.OutreachDeliveryRepository repo) {
        this.deliveries = repo;
    }

    public DefaultOutreachService(SystemSenderService senders,
                                  List<RecipientResolver> resolvers,
                                  ConversationEngine engine,
                                  RecorderRouter recorderRouter,
                                  ChatEntryPointRepository entryPoints,
                                  OutreachSessionRepository outreachSessions,
                                  ChatInboxRepository chatInbox,
                                  SystemInboundMessageRepository ledger,
                                  MediationFramer framer,
                                  MediationService mediation,
                                  com.yayatechandinnovations.yayaagentic.outreach.media.MediaLibraryService mediaLibrary,
                                  com.yayatechandinnovations.yayaagentic.outreach.push.PushNotifier pushNotifier,
                                  ObjectMapper json) {
        this.senders = senders;
        this.resolvers = resolvers;
        this.engine = engine;
        this.recorderRouter = recorderRouter;
        this.entryPoints = entryPoints;
        this.outreachSessions = outreachSessions;
        this.chatInbox = chatInbox;
        this.ledger = ledger;
        this.framer = framer;
        this.mediation = mediation;
        this.mediaLibrary = mediaLibrary;
        this.pushNotifier = pushNotifier;
        this.json = json;
    }

    @Override
    public OutreachReceipt ingest(SystemInboundMessage msg, OutreachAuth auth) {
        SystemSender sender = senders.find(msg.tenant(), msg.senderId())
                .orElseThrow(() -> new OutreachException(OutreachException.Kind.SENDER_DISABLED,
                        "unknown sender"));
        if (!sender.enabled()) {
            throw new OutreachException(OutreachException.Kind.SENDER_DISABLED,
                    "sender is not enabled for inbound");
        }
        if (!sender.addressing().permits(msg.recipient())) {
            // A policy violation the trusted caller should learn about (not a
            // silent drop) — but we never echo the recipient back.
            throw new OutreachException(OutreachException.Kind.ADDRESSING_DENIED,
                    "sender is not permitted to address this recipient");
        }

        // --- Idempotency: claim the dedup key by inserting the ledger row first.
        // A replay loses the race on the unique (tenant, sender, dedup_key) index.
        Optional<SystemInboundMessageEntity> existing =
                ledger.findByTenantIdAndSenderIdAndDedupKey(
                        msg.tenant().value(), msg.senderId().value(), msg.dedupKey());
        if (existing.isPresent()) {
            return duplicateReceipt(existing.get());
        }

        Ids.OutreachId outreachId = new Ids.OutreachId(UUID.randomUUID().toString());
        SystemInboundMessageEntity ledgerRow = new SystemInboundMessageEntity(
                outreachId.value(), msg.tenant().value(), msg.senderId().value(), msg.dedupKey());
        ledgerRow.setRecipientJson(writeJson(recipientMap(msg.recipient())));
        ledgerRow.setBody(msg.body());
        ledgerRow.setCorrelationJson(writeJson(correlationMap(msg)));
        ledgerRow.setMetadataJson(writeJson(msg.metadata()));
        ledgerRow.setStatus("RECEIVED");
        try {
            ledger.saveAndFlush(ledgerRow);
        } catch (DataIntegrityViolationException dup) {
            // Lost the dedup race — treat as duplicate.
            return ledger.findByTenantIdAndSenderIdAndDedupKey(
                            msg.tenant().value(), msg.senderId().value(), msg.dedupKey())
                    .map(this::duplicateReceipt)
                    .orElseThrow(() -> dup);
        }

        // --- Resolve to delivery targets. Empty ⇒ nobody eligible ⇒ silent drop.
        List<ResolvedTarget> targets = resolve(msg.recipient(), sender);
        if (targets.isEmpty()) {
            ledgerRow.setStatus("DROPPED");
            ledger.save(ledgerRow);
            return new OutreachReceipt(outreachId, Disposition.DROPPED, List.of(),
                    ledgerRow.getReceivedAt() == null ? Instant.now() : ledgerRow.getReceivedAt().toInstant());
        }

        List<String> sessionIds = new ArrayList<>();
        List<Delivery> receipts = new ArrayList<>();
        for (ResolvedTarget target : targets) {
            Delivered d = deliver(msg, sender, target, outreachId);
            if (d.sessionId() != null) sessionIds.add(d.sessionId());
            receipts.add(d.delivery());
        }

        ledgerRow.setStatus("DELIVERED");
        ledgerRow.setSessionId(sessionIds.isEmpty() ? null : sessionIds.get(0));
        ledger.save(ledgerRow);
        return new OutreachReceipt(outreachId, Disposition.DELIVERED, sessionIds,
                ledgerRow.getReceivedAt() == null ? Instant.now() : ledgerRow.getReceivedAt().toInstant(),
                receipts);
    }

    @Override
    public Optional<OutreachStatus> status(Ids.OutreachId id) {
        return ledger.findById(id.value())
                .map(e -> new OutreachStatus(new Ids.OutreachId(e.getId()), e.getStatus(),
                        e.getReceivedAt() == null ? Instant.now() : e.getReceivedAt().toInstant(),
                        deliveries == null ? List.of() : deliveries.findByTenantIdAndOutreachIdOrderByIdAsc(e.getTenantId(), e.getId())
                                .stream().map(DefaultOutreachService::toDelivery).toList()));
    }

    @Override
    public Optional<OutreachStatus> statusFor(Ids.TenantId tenant, Ids.SystemSenderId sender, Ids.OutreachId id) {
        return ledger.findById(id.value())
                .filter(e -> tenant.value().equals(e.getTenantId()) && sender.value().equals(e.getSenderId()))
                .flatMap(e -> status(id));
    }

    private static Delivery toDelivery(com.yayatechandinnovations.yayaagentic.persistence.OutreachDeliveryEntity e) {
        return new Delivery(e.getChannel(), e.getFallbackReason(), e.getProviderMessageSid(), e.getStatus(),
                e.getStatusAt() == null ? null : e.getStatusAt().toInstant());
    }

    /** One target's outcome: the chat session it landed in (null on WhatsApp) + its receipt. */
    private record Delivered(String sessionId, Delivery delivery) {}

    /* ---------------------------- delivery ------------------------------- */

    /**
     * Deliver to one target. A WHATSAPP preference goes out on the sender's
     * linked WhatsApp number when it can; every reason it cannot falls back to
     * the chat inbox with that reason on the receipt (§6.1) — never a drop.
     */
    private Delivered deliver(SystemInboundMessage msg, SystemSender sender,
                              ResolvedTarget target, Ids.OutreachId outreachId) {
        String phone = msg.delivery().phoneE164();
        List<ActionItem> actions = actionService == null ? msg.actions()
                : actionService.register(msg.tenant(), outreachId, msg.actions(), target.ownerSubject(), phone);

        String fallbackReason = null;
        if (msg.delivery().wantsWhatsApp()) {
            if (whatsappLeg == null) {
                fallbackReason = "provider_not_configured";
            } else {
                var outcome = whatsappLeg.deliver(new com.yayatechandinnovations.yayaagentic.channel.whatsapp
                        .WhatsAppOutreachLeg.Request(msg.tenant(), sender.whatsappSenderId().orElse(null), phone,
                        msg.body(), whatsappMedia(msg), actions, msg.delivery().template()));
                if (outcome.delivered()) {
                    var row = recordDelivery(msg, target, outreachId, "WHATSAPP", "SENT", null);
                    if (row != null) {
                        row.setWhatsappSenderId(sender.whatsappSenderId().orElse(null));
                        row.setEndUserPhone(phone);
                        row.setTemplateName(outcome.templateName());
                        row.setProviderMessageSid(outcome.providerMessageSid());
                        row.setBody(outcome.deliveredBody());
                        deliveries.save(row);
                    }
                    return new Delivered(null, new Delivery("WHATSAPP", null, outcome.providerMessageSid(),
                            "SENT", Instant.now()));
                }
                fallbackReason = outcome.fallbackReason();
                log.info("outreach {} → chat fallback for {}: {}", outreachId.value(),
                        target.recipientKey(), fallbackReason);
            }
        }
        String sessionId = deliverToChat(msg, sender, target, outreachId, actions);
        var row = recordDelivery(msg, target, outreachId, "CHAT", "CHAT_DELIVERED", fallbackReason);
        if (row != null) deliveries.save(row);
        return new Delivered(sessionId, new Delivery("CHAT", fallbackReason, null, "CHAT_DELIVERED", Instant.now()));
    }

    private com.yayatechandinnovations.yayaagentic.persistence.OutreachDeliveryEntity recordDelivery(
            SystemInboundMessage msg, ResolvedTarget target, Ids.OutreachId outreachId,
            String channel, String status, String fallbackReason) {
        if (deliveries == null) return null;
        var row = new com.yayatechandinnovations.yayaagentic.persistence.OutreachDeliveryEntity(
                msg.tenant().value(), outreachId.value(), target.recipientKey(), channel, status);
        row.setFallbackReason(fallbackReason);
        return row;
    }

    private List<com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppOutreachLeg.Media> whatsappMedia(
            SystemInboundMessage msg) {
        List<com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppOutreachLeg.Media> out = new ArrayList<>();
        for (com.yayatechandinnovations.yayaagentic.channel.system.MediaRef m : msg.media()) {
            mediaLibrary.find(msg.tenant(), m.assetId()).ifPresent(a -> out.add(
                    new com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppOutreachLeg.Media(
                            a.id(), a.filename(), a.contentType())));
        }
        return out;
    }

    /** Record the message into the target's outreach session and enqueue it for
     *  chat drain. Returns the session id delivered into. */
    private String deliverToChat(SystemInboundMessage msg, SystemSender sender,
                                 ResolvedTarget target, Ids.OutreachId outreachId, List<ActionItem> actions) {
        OutreachSession os = findOrCreateSession(msg.tenant(), target);
        Session sessionForRouting = os.asSession(msg.tenant(), target.ownerSubject());
        ConversationRecorder recorder = recorderRouter.recorderFor(sessionForRouting,
                new RecorderRouter.RecordingDecisionContext(Map.of()));

        Ids.SessionId sessionId = new Ids.SessionId(os.sessionId());
        int index = recorder.loadTurns(sessionId, TurnRange.all()).size();
        Ids.TurnId turnId = new Ids.TurnId(UUID.randomUUID().toString());

        boolean mediated = sender.mediation() == MediationMode.LLM_MEDIATED;
        // VERBATIM: the body is delivered as-is (no LLM round). LLM_MEDIATED: the
        // agent frames it — that framing IS the §9.1 Rule 1 inspection turn, run
        // with an empty toolset, so arrival can never cause a tool call.
        String deliveredBody = mediated
                ? framer.frame(sender.displayName(), msg.correlation().subject(), msg.body())
                : (msg.body() == null ? "" : msg.body());

        // Resolve media refs to tenant-owned assets (§10). Unknown / cross-tenant
        // asset ids are silently dropped — a sender can't exfiltrate others' media.
        List<Map<String, Object>> mediaList = new ArrayList<>();
        for (com.yayatechandinnovations.yayaagentic.channel.system.MediaRef m : msg.media()) {
            mediaLibrary.find(msg.tenant(), m.assetId()).ifPresent(a -> mediaList.add(new LinkedHashMap<>(Map.of(
                    "assetId", a.id(),
                    "contentType", a.contentType(),
                    "filename", a.filename(),
                    "sizeBytes", a.sizeBytes()))));
        }

        Map<String, Object> turnMeta = new LinkedHashMap<>();
        turnMeta.put("origin", ORIGIN_SYSTEM_PARTICIPANT);
        turnMeta.put("senderId", sender.id().value());
        turnMeta.put("senderDisplay", sender.displayName());
        turnMeta.put("mediation", sender.mediation().name());
        turnMeta.put("outreachId", outreachId.value());
        turnMeta.put("correlation", correlationMap(msg));
        if (!mediaList.isEmpty()) turnMeta.put("media", mediaList);
        if (mediated) turnMeta.put("rawBody", msg.body()); // audit: the untrusted original

        Turn turn = new Turn(turnId, sessionId, index, Turn.Role.SYSTEM, deliveredBody,
                List.of(), List.of(), List.of(), null, Map.copyOf(turnMeta), Instant.now());
        Principal owner = ownerPrincipal(msg.tenant(), target.ownerSubject());
        recorder.onTurnRecorded(sessionId, turn,
                new RecorderContext(msg.tenant(), owner, UUID.randomUUID().toString(), Map.of()));

        // LLM_MEDIATED: persist the durable correlation so the operator can reply
        // (route the answer back) even after being offline for days (§8).
        if (mediated) {
            mediation.recordPending(msg.tenant(), sessionId, sender, msg.correlation());
        }

        ChatInboxEntity inbox = new ChatInboxEntity(
                msg.tenant().value(), target.entryPointId(), target.recipientKey(), os.sessionId());
        inbox.setTurnId(turnId.value());
        inbox.setOutreachId(outreachId.value());
        inbox.setSenderDisplay(sender.displayName());
        inbox.setSubject(msg.correlation().subject());
        inbox.setBody(deliveredBody);
        inbox.setCorrelationJson(writeJson(correlationMap(msg)));
        inbox.setMediaJson(writeJson(mediaList));
        inbox.setActionsJson(writeJson(drainActions(actions)));
        chatInbox.save(inbox);

        // Best-effort wake nudge (§11 #3), gated by the sender's quiet hours (§3).
        // The message is already durably delivered — a suppressed/failed nudge only
        // delays notification, never the message.
        maybeWake(msg.tenant(), sender, target);

        return os.sessionId();
    }

    /**
     * What the chat client sees of each action: label, style, its id, and a
     * text to post for clients that predate bound actions. Tool ids and args
     * stay server-side — the id is all a tap needs.
     */
    private static List<Map<String, Object>> drainActions(List<ActionItem> actions) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (actions == null) return out;
        for (ActionItem a : actions) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", a.label());
            m.put("send", a.send() == null || a.send().isBlank() ? a.label() : a.send());
            m.put("style", a.style());
            if (a.actionId() != null) m.put("actionId", a.actionId());
            out.add(m);
        }
        return out;
    }

    /** The durable outreach session for (tenant, entry point, recipient) — reused
     *  across messages so one coherent thread accrues (§6 delivery-target). */
    private OutreachSession findOrCreateSession(Ids.TenantId tenant, ResolvedTarget target) {
        Optional<OutreachSessionEntity> existing =
                outreachSessions.findByTenantIdAndEntryPointIdAndRecipientKey(
                        tenant.value(), target.entryPointId(), target.recipientKey());
        if (existing.isPresent()) {
            OutreachSessionEntity e = existing.get();
            return new OutreachSession(e.getSessionId(), e.getProfileId(), e.getProfileVersion(),
                    e.getCreatedAt() == null ? Instant.now() : e.getCreatedAt().toInstant());
        }

        ChatEntryPointEntity ep = entryPoints.findByTenantIdAndId(tenant.value(), target.entryPointId())
                .orElseThrow(() -> new OutreachException(OutreachException.Kind.UNRESOLVABLE_RECIPIENT,
                        "delivery entry point no longer exists"));

        // Mint a session OWNED by the recipient (synthetic pre-verified principal,
        // §12) so the transcript is theirs and a later reply continues the thread.
        Principal owner = ownerPrincipal(tenant, target.ownerSubject());
        Map<String, Object> hints = new LinkedHashMap<>();
        hints.put("channel.kind", "chat");
        hints.put("channel.entryPoint", ep.getId());
        hints.put("channel.handle", ep.getHandle());
        int version = ep.getProfileVersion() == null ? 1 : ep.getProfileVersion();
        StartConversationRequest req = new StartConversationRequest(
                tenant, Optional.of(new Ids.ProfileId(ep.getProfileId(), version)), "chat", hints);
        StartSessionResult result = engine.start(req, new AuthContext(tenant, Map.of(), Optional.of(owner)));

        Ids.ProfileId pinned = result.profile();
        String sessionId = result.session().id().value();
        outreachSessions.save(new OutreachSessionEntity(
                tenant.value(), target.entryPointId(), target.recipientKey(),
                sessionId, pinned.value(), pinned.version()));
        return new OutreachSession(sessionId, pinned.value(), pinned.version(),
                result.session().createdAt());
    }

    private void maybeWake(Ids.TenantId tenant, SystemSender sender, ResolvedTarget target) {
        var limits = sender.limits().orElse(null);
        if (limits != null && limits.isQuietAt(Instant.now())) {
            // Inside do-not-disturb: delivered, but hold the buzz.
            return;
        }
        try {
            pushNotifier.wake(tenant, target.entryPointId(), target.recipientKey(), sender.displayName());
        } catch (RuntimeException e) {
            log.debug("push wake failed for {} (non-fatal)", target.recipientKey(), e);
        }
    }

    private List<ResolvedTarget> resolve(OutreachRecipient recipient, SystemSender sender) {
        return resolvers.stream()
                .filter(r -> r.supports(recipient))
                .findFirst()
                .map(r -> r.resolve(recipient, sender))
                .orElseThrow(() -> new OutreachException(OutreachException.Kind.UNRESOLVABLE_RECIPIENT,
                        "no resolver for recipient kind " + recipient.kindName()));
    }

    private static Principal ownerPrincipal(Ids.TenantId tenant, String subject) {
        // Minimal owner identity: subject fixes ownership; scopes are empty until
        // the operator authenticates for themselves (their reply turn, SM-2).
        return new Principal(subject, tenant, Set.of(),
                Map.of("outreach.synthetic", Boolean.TRUE), Instant.now());
    }

    private OutreachReceipt duplicateReceipt(SystemInboundMessageEntity e) {
        List<String> sessions = e.getSessionId() == null ? List.of() : List.of(e.getSessionId());
        return new OutreachReceipt(new Ids.OutreachId(e.getId()), Disposition.DUPLICATE, sessions,
                e.getReceivedAt() == null ? Instant.now() : e.getReceivedAt().toInstant());
    }

    private static Map<String, Object> recipientMap(OutreachRecipient r) {
        return Map.of("kind", r.kindName(), "key", r.key());
    }

    private static Map<String, Object> correlationMap(SystemInboundMessage msg) {
        var c = msg.correlation();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("originatorName", c.originatorName());
        m.put("originatorRef", c.originatorRef());
        m.put("subject", c.subject());
        return m;
    }

    private String writeJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to serialize outreach payload", ex);
        }
    }

    /** Lightweight view of an outreach session — enough to route a recorder and
     *  reconstruct a {@link Session} for the append. */
    private record OutreachSession(String sessionId, String profileId, int profileVersion, Instant createdAt) {
        Session asSession(Ids.TenantId tenant, String ownerSubject) {
            return new Session(
                    new Ids.SessionId(sessionId), tenant,
                    ownerPrincipal(tenant, ownerSubject),
                    new Ids.ProfileId(profileId, profileVersion),
                    "chat", List.of(), IntentFrame.empty(), Map.of(), createdAt, null);
        }
    }
}
