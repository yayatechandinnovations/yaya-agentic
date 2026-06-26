package com.yayatechandinnovations.yayaagentic.channel;

import java.util.Map;

/**
 * What the platform asks a {@link MessagingChannelProvider} to register on the
 * BSP. Channel-specific fields (e.g. the WhatsApp {@code BusinessProfile} from
 * §6.2) flow through {@code providerPayload} so this record stays BSP-agnostic;
 * Twilio's WhatsApp path renders the business profile into the Twilio Senders
 * API call from the payload.
 *
 * <p>Webhook URLs are set by the platform (§14.1) — they are NOT a tenant
 * concern, and they embed the platform's public base URL + the platform-stable
 * sender ID for routing.
 */
public record SenderRegistrationRequest(
        ChannelKind         channel,
        String              phoneE164,
        String              displayName,
        String              webhookUrl,
        String              statusCallbackUrl,
        Map<String, Object> providerPayload,
        String              idempotencyKey
) {

    public SenderRegistrationRequest {
        if (channel == null) {
            throw new IllegalArgumentException("channel required");
        }
        if (phoneE164 == null || phoneE164.isBlank()) {
            throw new IllegalArgumentException("phoneE164 must not be blank");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        if (webhookUrl == null || webhookUrl.isBlank()) {
            throw new IllegalArgumentException("webhookUrl must not be blank");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        providerPayload = providerPayload == null ? Map.of() : Map.copyOf(providerPayload);
    }
}
