package com.yayatechandinnovations.yayaagentic.channel;

/**
 * Marker for BSP credentials passed per call to
 * {@link MessagingChannelProvider}. Implementations are BSP-specific (e.g.
 * {@code TwilioCreds(accountSid, apiKeySid, apiKeySecret, messagingServiceSid)})
 * and never serialised — they exist only on the hot path between the
 * {@code ProviderCredsResolver} and the provider call.
 *
 * <p>The SPI carries creds as an argument rather than ambient state on
 * purpose: v1 platform-singleton always returns the same value, but the
 * shape doesn't dead-end the future BYO Twilio case (see
 * {@code whatsapp-channel-design.md} §7.2 + §7.3).
 */
public interface ProviderCreds {

    /** Which BSP these creds belong to. Cheap defensive check at provider entry. */
    ProviderId provider();
}
