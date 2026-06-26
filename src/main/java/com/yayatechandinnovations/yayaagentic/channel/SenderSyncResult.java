package com.yayatechandinnovations.yayaagentic.channel;

import java.time.Instant;

/**
 * Current BSP-side state for a sender. Returned by
 * {@link MessagingChannelProvider#syncSender} — the poller (§6.4) calls it
 * every 5m for senders in {@code SUBMITTING} or {@code PENDING_REVIEW} and
 * the result drives state transitions.
 *
 * @param bspStatus               raw status string from the BSP (e.g. Twilio
 *                                {@code ONLINE} / {@code OFFLINE} / {@code PENDING})
 * @param approvedDisplayName     null until Meta approves the display name;
 *                                signals the transition to {@code ACTIVE}
 * @param lastFailureCode         null when state is healthy
 * @param lastFailureMessage      null when state is healthy
 * @param polledAt                when the BSP was reached
 */
public record SenderSyncResult(
        SenderRef senderRef,
        String    bspStatus,
        String    approvedDisplayName,
        String    lastFailureCode,
        String    lastFailureMessage,
        Instant   polledAt
) {

    public SenderSyncResult {
        if (senderRef == null) {
            throw new IllegalArgumentException("senderRef required");
        }
        if (bspStatus == null || bspStatus.isBlank()) {
            throw new IllegalArgumentException("bspStatus must not be blank");
        }
        if (polledAt == null) {
            throw new IllegalArgumentException("polledAt required");
        }
    }
}
