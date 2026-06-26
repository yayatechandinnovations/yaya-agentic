package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

/**
 * Platform-stable identifier for a {@link WhatsAppSender}. Minted at
 * {@code DRAFT} time and safe to use as the Twilio idempotency key on
 * re-submission of an edited failed draft — by design, a {@code SenderId}
 * is contiguous across retries even though Twilio assigns a fresh
 * {@code SenderSid} on the second attempt (see
 * {@code whatsapp-channel-design.md} §6.3).
 *
 * <p>Lives at the channel layer (not {@code core/Ids.java}) because it's
 * channel-scoped — no engine-level code should know what a sender ID is.
 */
public record SenderId(String value) {

    public SenderId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("SenderId value must not be blank");
        }
    }
}
