package com.yayatechandinnovations.yayaagentic.outreach.push;

import com.yayatechandinnovations.yayaagentic.core.Ids;

/**
 * SPI #14 — best-effort app wake for chat delivery (§11 #3, §19). Sends a
 * content-free nudge so an idle client drains its inbox promptly. The {@code
 * chat_inbox} row is the source of truth; push only optimizes latency, so a
 * failed wake is never fatal — the message still arrives on next open.
 */
public interface PushNotifier {

    /**
     * Nudge every registered device for a recipient. Content-free by design: the
     * payload carries no message body, only "you have something — come drain".
     */
    void wake(Ids.TenantId tenant, String entryPointId, String recipientKey, String title);
}
