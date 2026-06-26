package com.yayatechandinnovations.yayaagentic.channel;

/**
 * Per-call context for {@link MessagingChannelAdapter#deliver}. Carries the
 * provider the adapter should drive (so the same WhatsApp adapter shape works
 * with Twilio today and MessageBird tomorrow) and the BSP credentials
 * resolved per the per-call lookup (§7.2).
 *
 * @param provider the {@link MessagingChannelProvider} to dispatch through
 * @param creds    BSP credentials for this dispatch; v1 always returns the
 *                 platform-singleton from
 *                 {@code PlatformSingletonTwilioCredsResolver} (§7.2)
 * @param trace   correlation id for OTel span linking between the inbound
 *                webhook trace and the background dispatch trace (§17)
 */
public record ChannelDeliveryContext(
        MessagingChannelProvider provider,
        ProviderCreds            creds,
        String                   trace
) {

    public ChannelDeliveryContext {
        if (provider == null) {
            throw new IllegalArgumentException("provider required");
        }
        if (creds == null) {
            throw new IllegalArgumentException("creds required");
        }
    }
}
