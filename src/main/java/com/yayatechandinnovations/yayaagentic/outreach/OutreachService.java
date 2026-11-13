package com.yayatechandinnovations.yayaagentic.outreach;

import com.yayatechandinnovations.yayaagentic.channel.system.SystemInboundMessage;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;

import java.time.Instant;
import java.util.Optional;

/**
 * SPI #12 — the single entry point that orchestrates the three legs of system
 * messaging (ingest → deliver/mediate → respond). Peer to {@code
 * ConversationEngine}. See {@code system-messaging-design.md} §13, §19.
 *
 * <p>{@link #ingest} is idempotent on the message's {@code dedupKey}: a replayed
 * post returns the original receipt without re-delivering.
 */
public interface OutreachService {

    /** Legs 1→2: authenticate-verified message in, recorded + delivered out. */
    OutreachReceipt ingest(SystemInboundMessage msg, OutreachAuth auth);

    Optional<OutreachStatus> status(Ids.OutreachId id);

    /**
     * Status of one ingest as its own sender sees it — per-target delivery
     * channel and provider state (whatsapp-ops-integration-design.md §14.2).
     * Empty when the id is unknown or belongs to another sender.
     */
    default Optional<OutreachStatus> statusFor(Ids.TenantId tenant, Ids.SystemSenderId sender,
                                               Ids.OutreachId id) {
        return Optional.empty();
    }

    /** The verified sender identity + request-integrity facts the ingest edge established. */
    record OutreachAuth(Principal principal) {}

    enum Disposition { DELIVERED, DUPLICATE, DROPPED }

    /**
     * @param id          the ingested-message id (stable across idempotent replays)
     * @param disposition what happened to it
     * @param sessionIds  the outreach sessions it was delivered into (empty when dropped)
     */
    record OutreachReceipt(
            Ids.OutreachId id,
            Disposition disposition,
            java.util.List<String> sessionIds,
            Instant receivedAt,
            java.util.List<Delivery> deliveries
    ) {
        public OutreachReceipt {
            deliveries = deliveries == null ? java.util.List.of() : java.util.List.copyOf(deliveries);
        }

        public OutreachReceipt(Ids.OutreachId id, Disposition disposition,
                               java.util.List<String> sessionIds, Instant receivedAt) {
            this(id, disposition, sessionIds, receivedAt, java.util.List.of());
        }
    }

    /**
     * How one resolved target got the message (whatsapp-ops-integration-design.md
     * §14.1/§14.2): {@code channel} ∈ CHAT/WHATSAPP, {@code fallbackReason} set
     * when WhatsApp was asked for but the chat carried it, {@code status} ∈
     * QUEUED/SENT/DELIVERED/READ/FAILED/CHAT_DELIVERED.
     */
    record Delivery(String channel, String fallbackReason, String providerMessageId,
                    String status, Instant statusAt) {}

    record OutreachStatus(
            Ids.OutreachId id,
            String status,
            Instant receivedAt,
            java.util.List<Delivery> deliveries
    ) {
        public OutreachStatus {
            deliveries = deliveries == null ? java.util.List.of() : java.util.List.copyOf(deliveries);
        }

        public OutreachStatus(Ids.OutreachId id, String status, Instant receivedAt) {
            this(id, status, receivedAt, java.util.List.of());
        }
    }
}
