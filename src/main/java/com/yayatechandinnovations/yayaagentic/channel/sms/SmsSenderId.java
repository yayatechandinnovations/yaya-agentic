package com.yayatechandinnovations.yayaagentic.channel.sms;

/**
 * Platform-stable identifier for an {@link SmsSender} — a registered SMS
 * phone-number endpoint. Minted at {@code DRAFT} time and used as the carrier
 * idempotency key on (re)submission, exactly like
 * {@code channel.whatsapp.SenderId}.
 */
public record SmsSenderId(String value) {

    public SmsSenderId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("SmsSenderId value must not be blank");
        }
    }
}
