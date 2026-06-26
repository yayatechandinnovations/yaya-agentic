package com.yayatechandinnovations.yayaagentic.channel;

/**
 * What we trust for inbound signature verification on a single sender. The
 * webhook controller resolves the sender, fetches this material, and hands it
 * to {@link MessagingChannelProvider#validateInbound}. The material is
 * unique-per-sender by design — one compromised sender's secret can't be used
 * to forge inbound to another (see {@code whatsapp-channel-design.md} §13
 * note about {@code whatsapp_senders.webhook_secret_enc}).
 *
 * @param senderRef     the BSP-side sender this material protects
 * @param webhookSecret the shared secret embedded in the Twilio webhook URL;
 *                      used to compute the expected {@code X-Twilio-Signature}
 */
public record SenderTrustMaterial(SenderRef senderRef, String webhookSecret) {

    public SenderTrustMaterial {
        if (senderRef == null) {
            throw new IllegalArgumentException("senderRef required");
        }
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new IllegalArgumentException("webhookSecret must not be blank");
        }
    }
}
