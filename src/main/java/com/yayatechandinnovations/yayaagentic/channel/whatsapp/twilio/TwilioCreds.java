package com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio;

import com.yayatechandinnovations.yayaagentic.channel.ProviderCreds;
import com.yayatechandinnovations.yayaagentic.channel.ProviderId;

/**
 * Twilio API credentials wrapped as a {@link ProviderCreds}. Resolved per
 * call (not held as ambient state) so the v1 platform-singleton resolver and
 * a future BYO-Twilio resolver share the same wire-up — see
 * {@code whatsapp-channel-design.md} §7.2, §7.3.
 *
 * <p>Field order matches Twilio's API expectations: an API key SID + secret
 * is the recommended auth pair (replaces classic {@code AuthToken} usage),
 * scoped at the account level for the platform-singleton path. The
 * {@code messagingServiceSid} is optional — when present, dispatch uses
 * {@code MessagingServiceSid} in place of {@code From} for throughput
 * pooling and sticky-sender routing (§7.4).
 */
public record TwilioCreds(
        String accountSid,
        String apiKeySid,
        String apiKeySecret,
        String messagingServiceSid
) implements ProviderCreds {

    public TwilioCreds {
        if (accountSid == null || accountSid.isBlank()) {
            throw new IllegalArgumentException("accountSid required");
        }
        if (apiKeySid == null || apiKeySid.isBlank()) {
            throw new IllegalArgumentException("apiKeySid required");
        }
        if (apiKeySecret == null || apiKeySecret.isBlank()) {
            throw new IllegalArgumentException("apiKeySecret required");
        }
    }

    @Override
    public ProviderId provider() {
        return ProviderId.TWILIO;
    }
}
