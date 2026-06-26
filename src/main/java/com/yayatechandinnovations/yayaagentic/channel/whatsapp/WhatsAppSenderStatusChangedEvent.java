package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.yayatechandinnovations.yayaagentic.core.Ids;

/**
 * Published whenever a sender's status changes — submit, poller observation,
 * operator-driven suspend/resume/revoke. Consumers:
 *
 * <ul>
 *   <li>W3 binding-resolver cache — evict entries for the sender when its
 *       status leaves / enters ACTIVE so routing doesn't honour a paused
 *       sender for a turn.</li>
 *   <li>W5 outbound dispatcher cache — same eviction reason.</li>
 *   <li>(future) Operator audit + email notifications on ACTIVE / FAILED.</li>
 * </ul>
 *
 * <p>The event carries enough context for listeners to act without a fresh
 * DB read.
 */
public record WhatsAppSenderStatusChangedEvent(
        Ids.TenantId   tenant,
        SenderId       sender,
        SenderStatus   from,
        SenderStatus   to,
        String         reason
) {

    public WhatsAppSenderStatusChangedEvent {
        if (tenant == null) throw new IllegalArgumentException("tenant required");
        if (sender == null) throw new IllegalArgumentException("sender required");
        if (from == null)   throw new IllegalArgumentException("from required");
        if (to == null)     throw new IllegalArgumentException("to required");
    }
}
