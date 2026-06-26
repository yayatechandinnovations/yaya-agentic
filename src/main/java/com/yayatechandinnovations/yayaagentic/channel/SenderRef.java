package com.yayatechandinnovations.yayaagentic.channel;

/**
 * BSP-side reference to a registered sender. For Twilio WhatsApp this wraps a
 * {@code SR...} sender SID — only populated after the first successful
 * {@code registerSender} call (see {@code whatsapp-channel-design.md} §6.3 —
 * why {@code SenderId} ≠ {@code twilioSenderSid}).
 *
 * <p>The platform-internal {@code SenderId} (the one we mint at DRAFT time and
 * use as the Twilio idempotency key) lives in the WhatsApp-specific package
 * once W1 lands; this record is the BSP-facing reference, used by the
 * {@link MessagingChannelProvider} SPI.
 */
public record SenderRef(ProviderId provider, String externalId) {

    public SenderRef {
        if (provider == null) {
            throw new IllegalArgumentException("provider required");
        }
        if (externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("externalId must not be blank");
        }
    }
}
