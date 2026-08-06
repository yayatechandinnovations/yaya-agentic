package com.yayatechandinnovations.yayaagentic.channel;

/**
 * What we trust for inbound signature verification on a single sender. The
 * webhook controller resolves the sender, fetches this material, and hands it
 * to {@link MessagingChannelProvider#validateInbound}. The material is
 * unique-per-sender by design — one compromised sender's secret can't be used
 * to forge inbound to another (see {@code whatsapp-channel-design.md} §13
 * note about {@code whatsapp_senders.webhook_secret_enc}).
 *
 * @param senderRef        the BSP-side sender this material protects
 * @param webhookSecret    the shared secret embedded in the Twilio webhook URL;
 *                         used to compute the expected {@code X-Twilio-Signature}
 * @param signingAuthToken the auth token of the Twilio (sub)account that signs
 *                         this sender's webhooks. Non-null for senders owned by
 *                         a tenant messaging account (Embedded Signup /
 *                         subaccount model); null → the verifier falls back to
 *                         the platform account's auth token
 */
public record SenderTrustMaterial(SenderRef senderRef, String webhookSecret,
                                  String signingAuthToken) {

    public SenderTrustMaterial {
        if (senderRef == null) {
            throw new IllegalArgumentException("senderRef required");
        }
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new IllegalArgumentException("webhookSecret must not be blank");
        }
    }

    /** Back-compat: sender signed by the platform account's auth token. */
    public SenderTrustMaterial(SenderRef senderRef, String webhookSecret) {
        this(senderRef, webhookSecret, null);
    }
}
