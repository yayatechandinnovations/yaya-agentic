package com.yayatechandinnovations.yayaagentic.channel;

import java.util.Set;

/**
 * Per-BSP integration surface. Twilio is the only implementation in v1; the
 * shape admits MessageBird, 360dialog, and direct Meta Cloud API later by
 * dropping in a sibling impl — no engine changes.
 *
 * <p>The SPI carries {@link ProviderCreds} per call (not as ambient state) so
 * the v1 platform-singleton resolver and the future BYO-Twilio resolver share
 * the same wire-up — see {@code whatsapp-channel-design.md} §7.2, §7.3. A
 * provider MAY defensively check that {@code creds.provider().equals(id())}
 * and throw {@code IllegalArgumentException} on mismatch.
 *
 * <p>Companion SPI: {@link MessagingChannelAdapter}. The split is intentional
 * — provider is per-BSP (Twilio's API surface), adapter is per-channel-kind
 * (how WhatsApp behaves) independent of BSP. See §5.2.
 */
public interface MessagingChannelProvider {

    /** Stable identifier ({@code "twilio"}). */
    ProviderId id();

    /** Which channel kinds this BSP can speak. */
    Set<ChannelKind> supports();

    /* ------------------------------ Sender lifecycle (§6) ----------------- */

    SenderRegistrationResult registerSender(SenderRegistrationRequest req, ProviderCreds creds);

    SenderSyncResult syncSender(SenderRef ref, ProviderCreds creds);

    void revokeSender(SenderRef ref, ProviderCreds creds);

    /**
     * Complete number-ownership verification (§6) with the one-time code the
     * BSP sent to the phone. Only numbers the BSP doesn't own need this; a
     * BSP that verifies every number itself never advertises it.
     */
    default SenderSyncResult verifySender(SenderRef ref, String verificationCode, ProviderCreds creds) {
        throw new UnsupportedOperationException("verifySender not supported by " + id());
    }

    /* ------------------------------ Inbound trust (§9) -------------------- */

    InboundValidation validateInbound(RawInboundRequest req, SenderTrustMaterial trust);

    /* ------------------------------ Outbound dispatch (§10) --------------- */

    OutboundResult sendFreeform(SenderRef ref, OutboundFreeform msg, ProviderCreds creds);

    OutboundResult sendTemplate(SenderRef ref, OutboundTemplate msg, ProviderCreds creds);

    /**
     * Send a media message (§10, §13). Backward-compatible default so BSPs that
     * can't (yet) carry attachments simply don't advertise the capability. A
     * media-capable BSP overrides this to POST the media to its API.
     */
    default OutboundResult sendMedia(SenderRef ref, OutboundMedia msg, ProviderCreds creds) {
        throw new UnsupportedOperationException("sendMedia not supported by " + id());
    }

    /* ------------------------------ Templates (§11) ----------------------- */

    TemplateSyncResult syncTemplates(SenderRef ref, ProviderCreds creds);
}
