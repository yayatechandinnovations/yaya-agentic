package com.yayatechandinnovations.yayaagentic.channel.whatsapp.shared;

import com.yayatechandinnovations.yayaagentic.channel.whatsapp.OutboundDispatcher;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.UnresolvedAutoReplyService;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSender;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSenderService;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.InboundMessageEntity;
import com.yayatechandinnovations.yayaagentic.persistence.InboundMessageRepository;
import com.yayatechandinnovations.yayaagentic.persistence.OutboundMessageEntity;
import com.yayatechandinnovations.yayaagentic.persistence.OutboundMessageRepository;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppSenderAssignmentEntity;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppSenderAssignmentRepository;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppSenderMemberEntity;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppSenderMemberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The inbound worker's side of a platform sender
 * (whatsapp-shared-sender-design.md §7): run the router and act on what it
 * decided, decide whether the engine may run for the routed workspace, and
 * answer everyone else with the fixed non-member reply.
 */
@Component
public class SharedInboundGate {

    private static final Logger log = LoggerFactory.getLogger(SharedInboundGate.class);

    static final String PICK_QUESTION = "¿Con cuál negocio quieres hablar?";
    static final String DEFAULT_UNROUTED_ES =
            "Este número solo envía avisos de los negocios que lo usan. Responde STOP para no recibir más.";
    static final String DEFAULT_UNROUTED_EN =
            "This number only sends notices from the businesses that use it. Reply STOP to stop them.";

    private final SharedInboundRouter router;
    private final WhatsAppSenderService senders;
    private final WhatsAppSenderAssignmentRepository assignments;
    private final WhatsAppSenderMemberRepository members;
    private final InboundMessageRepository inbound;
    private final OutboundMessageRepository outbound;
    private final OutboundDispatcher dispatcher;
    private final UnresolvedAutoReplyService autoReplies;

    public SharedInboundGate(SharedInboundRouter router,
                             WhatsAppSenderService senders,
                             WhatsAppSenderAssignmentRepository assignments,
                             WhatsAppSenderMemberRepository members,
                             InboundMessageRepository inbound,
                             OutboundMessageRepository outbound,
                             OutboundDispatcher dispatcher,
                             UnresolvedAutoReplyService autoReplies) {
        this.router = router;
        this.senders = senders;
        this.assignments = assignments;
        this.members = members;
        this.inbound = inbound;
        this.outbound = outbound;
        this.dispatcher = dispatcher;
        this.autoReplies = autoReplies;
    }

    /**
     * What the worker does next with a platform-sender inbound.
     *
     * @param proceed the row now carries the routed workspace; continue in it
     * @param replay  a held message to process now (in its workspace), after this one is filed
     */
    public record Outcome(boolean proceed, Optional<String> replay) {
        static Outcome stop() { return new Outcome(false, Optional.empty()); }
        static Outcome go() { return new Outcome(true, Optional.empty()); }
    }

    /** Route an inbound that has no workspace yet; files the row when it goes no further. */
    public Outcome route(WhatsAppSender sender, InboundMessageEntity row, Map<String, Object> raw, String body) {
        SharedInboundRouter.RouteDecision decision = router.route(sender, row, raw);
        switch (decision) {
            case SharedInboundRouter.RouteDecision.Routed r -> {
                row.setTenantId(r.tenant().value());
                row.setRouteReason(r.reason());
                inbound.save(row);
                boolean answerOnly = "pick".equals(r.reason()) || "switch".equals(r.reason());
                if (r.announce() || answerOnly) say(sender, row.getEndUserPhone(), announcement(r.label()));
                if (r.replay().isPresent()) {
                    file(row, "org_choice_answer", body);
                    return replay(r.replay().get(), r.tenant(), r.reason());
                }
                if (answerOnly) {
                    file(row, "org_choice_answer", body);
                    return Outcome.stop();
                }
                return Outcome.go();
            }
            case SharedInboundRouter.RouteDecision.Ask a -> {
                List<String> labels = a.candidates().stream().map(SharedInboundRouter.Candidate::label).toList();
                try {
                    dispatcher.dispatchGreeting(null, sender.id(), row.getEndUserPhone(),
                            new Ids.SessionId(UUID.randomUUID().toString()), PICK_QUESTION, labels);
                } catch (RuntimeException e) {
                    log.warn("org pick question not sent for {}: {}", row.getMessageSid(), e.getMessage());
                }
                file(row, a.holdMessage() ? "awaiting_org_choice" : "org_choice_asked", body);
                return Outcome.stop();
            }
            case SharedInboundRouter.RouteDecision.NotRouted n -> {
                replyToNonMember(sender, row.getEndUserPhone(), n.attributedTo());
                file(row, "not_routed", body);
                return Outcome.stop();
            }
        }
    }

    /** §7.5 condition 1 — the phone is staff of the workspace the message routed to. */
    public boolean isMember(Ids.TenantId tenant, WhatsAppSender sender, String phone) {
        return members.existsById(new WhatsAppSenderMemberEntity.PK(sender.id().value(), tenant.value(), phone));
    }

    /**
     * The fixed line a non-member gets (§8.3): the workspace's own reply when
     * the message can be pinned on one, the number's generic one otherwise.
     * Once per phone per 24 h.
     */
    public void replyToNonMember(WhatsAppSender sender, String phone, Optional<Ids.TenantId> attributedTo) {
        String text = attributedTo
                .flatMap(t -> assignments.findLive(sender.id().value(), t.value()))
                .map(WhatsAppSenderAssignmentEntity::getNonMemberReply)
                .filter(s -> !s.isBlank())
                .or(() -> senders.unroutedReply(sender.id()))
                .orElse(sender.defaultLocale() != null && sender.defaultLocale().toLowerCase().startsWith("en")
                        ? DEFAULT_UNROUTED_EN : DEFAULT_UNROUTED_ES);
        if (autoReplies != null && !autoReplies.claim(sender.id(), phone)) return;
        say(sender, phone, text);
    }

    /** The workspace that wrote to the phone last on this number — a STOP counts against it (§9.4). */
    public Optional<String> lastWriter(SenderId sender, String phone) {
        OffsetDateTime since = OffsetDateTime.now(ZoneOffset.UTC).minusDays(30);
        return outbound.findTop50BySenderIdAndEndUserPhoneAndSentAtAfterOrderBySentAtDesc(sender.value(), phone, since)
                .stream()
                .map(OutboundMessageEntity::getTenantId)
                .filter(t -> t != null)
                .findFirst();
    }

    static String announcement(String label) {
        return "Estás hablando con *" + label + "*. Escribe *CAMBIAR NEGOCIO* para cambiar.";
    }

    /* ------------------------------------------------------------------ */

    private Outcome replay(String heldSid, Ids.TenantId tenant, String reason) {
        Optional<InboundMessageEntity> held = inbound.findById(heldSid);
        if (held.isEmpty()) return Outcome.stop();
        InboundMessageEntity h = held.get();
        h.setTenantId(tenant.value());
        h.setRouteReason(reason);
        h.setProcessedAt(null);
        h.setProcessingError(null);
        inbound.save(h);
        return new Outcome(false, Optional.of(heldSid));
    }

    private void say(WhatsAppSender sender, String phone, String text) {
        try {
            // The platform's own line: no workspace, so no business label in front.
            dispatcher.dispatchText(null, sender.id(), phone, new Ids.SessionId(UUID.randomUUID().toString()), text);
        } catch (RuntimeException e) {
            log.warn("platform line to {} on {} not sent: {}", mask(phone), sender.id().value(), e.getMessage());
        }
    }

    private void file(InboundMessageEntity row, String reason, String body) {
        row.setProcessedAt(OffsetDateTime.now(ZoneOffset.UTC));
        row.setProcessingError(reason);
        row.setBodyRedacted(body);
        inbound.save(row);
    }

    private static String mask(String phone) {
        return phone == null || phone.length() < 4 ? "****" : "****" + phone.substring(phone.length() - 4);
    }
}
