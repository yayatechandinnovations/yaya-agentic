package com.yayatechandinnovations.yayaagentic.channel;

import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSender;

/**
 * Resolves the BSP credentials to use when speaking for a given sender. In
 * v1 there is exactly one resolver and it returns the platform credential
 * regardless of tenant; the SPI shape is what keeps the future BYO Twilio
 * model a one-resolver-swap change (see
 * {@code whatsapp-channel-design.md} §7.2 + §7.3).
 *
 * <p>Implementations MUST be idempotent and cheap on the hot path —
 * {@link MessagingChannelProvider} calls resolve creds per dispatch.
 */
public interface ProviderCredsResolver {

    /**
     * @throws ProviderNotConfiguredException when the BSP isn't configured
     *         at all (e.g. dev boot without {@code TWILIO_ACCOUNT_SID}).
     *         Callers translate this into a typed 409/503 — never silently
     *         no-op.
     */
    ProviderCreds resolve(WhatsAppSender sender) throws ProviderNotConfiguredException;

    /**
     * Cheap pre-flight check the controllers and the WhatsApp wizard use to
     * gate {@code submit}. Returns false when the platform isn't ready to
     * speak to the BSP.
     */
    boolean isReady();

    /** Thrown by {@link #resolve} when the BSP isn't configured. */
    class ProviderNotConfiguredException extends RuntimeException {
        public ProviderNotConfiguredException(String msg) { super(msg); }
    }
}
