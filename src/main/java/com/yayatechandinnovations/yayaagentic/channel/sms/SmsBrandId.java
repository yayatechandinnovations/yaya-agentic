package com.yayatechandinnovations.yayaagentic.channel.sms;

/**
 * Platform-stable identifier for an {@link SmsBrand} — the tenant's registered
 * business identity with the carriers (A2P 10DLC). Minted at {@code DRAFT}
 * time and safe to use as the Twilio idempotency key on re-submission of an
 * edited failed draft, exactly like {@code channel.whatsapp.SenderId} (see
 * {@code sms-channel-design.md} §6).
 *
 * <p>Lives at the channel layer (not {@code core/Ids.java}) because it's
 * channel-scoped — no engine-level code should know what a Brand is.
 */
public record SmsBrandId(String value) {

    public SmsBrandId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("SmsBrandId value must not be blank");
        }
    }
}
