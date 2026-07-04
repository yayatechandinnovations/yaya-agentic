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
            Instant receivedAt
    ) {}

    record OutreachStatus(
            Ids.OutreachId id,
            String status,
            Instant receivedAt
    ) {}
}
