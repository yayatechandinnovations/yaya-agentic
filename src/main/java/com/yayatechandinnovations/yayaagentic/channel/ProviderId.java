package com.yayatechandinnovations.yayaagentic.channel;

/**
 * Stable identifier for a {@link MessagingChannelProvider} (the BSP). Used in
 * audit, observability labels, and the provider-creds lookup. v1: only
 * {@code "twilio"} is registered.
 *
 * <p>See {@code whatsapp-channel-design.md} §5.1.
 */
public record ProviderId(String value) {

    public static final ProviderId TWILIO = new ProviderId("twilio");

    public ProviderId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("ProviderId value must not be blank");
        }
    }
}
