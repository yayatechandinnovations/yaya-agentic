package com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Platform-singleton Twilio configuration bound from
 * {@code yaya.agentic.channels.twilio.*} per
 * {@code whatsapp-channel-design.md} §7.1.
 *
 * <p>All four credential fields are nullable so dev / test boot without Twilio
 * configured at all — {@link #isConfigured()} is the cheap "are we wired?"
 * check that the startup probe and the WhatsApp wizard gating use. When
 * unconfigured the WhatsApp channel disables itself rather than failing
 * health checks (mirrors how the email provider degrades to
 * dev-console without {@code SENDGRID_API_KEY}).
 *
 * @param accountSid           Twilio account SID ({@code AC...})
 * @param apiKeySid            Twilio API key SID ({@code SK...})
 * @param apiKeySecret         Twilio API key secret — read from vault by
 *                             Spring property resolution; never persisted by
 *                             us, never logged
 * @param webhookBaseUrl       hostname Twilio dials back into for inbound +
 *                             status callbacks. Empty = fall back to
 *                             {@code yaya.agentic.public-base-url} via the
 *                             resolver
 * @param messagingServiceSid  optional Messaging Service SID
 *                             ({@code MG...}) — when set, outbound dispatch
 *                             uses {@code MessagingServiceSid} in place of
 *                             {@code From}
 */
@ConfigurationProperties(prefix = "yaya.agentic.channels.twilio")
public record TwilioProperties(
        String accountSid,
        String apiKeySid,
        String apiKeySecret,
        /** Twilio Account Auth Token — used by Twilio to sign inbound
         *  webhooks ({@code X-Twilio-Signature}). Distinct from the API key
         *  secret used for outbound Twilio API calls. */
        String authToken,
        String webhookBaseUrl,
        String messagingServiceSid,
        /** Base URL for {@code api.twilio.com} surface (Accounts, Messages API).
         *  Override only when stubbing Twilio in tests. */
        String apiBaseUrl,
        /** Base URL for {@code messaging.twilio.com} surface (Channel Senders,
         *  Content API). Override only when stubbing Twilio in tests. */
        String messagingBaseUrl
) {

    public static final String DEFAULT_API_BASE_URL = "https://api.twilio.com";
    public static final String DEFAULT_MESSAGING_BASE_URL = "https://messaging.twilio.com";
    public static final String DEFAULT_CONTENT_BASE_URL = "https://content.twilio.com";

    public boolean isConfigured() {
        return notBlank(accountSid) && notBlank(apiKeySid) && notBlank(apiKeySecret);
    }

    public String effectiveApiBaseUrl() {
        return notBlank(apiBaseUrl) ? apiBaseUrl : DEFAULT_API_BASE_URL;
    }

    public String effectiveMessagingBaseUrl() {
        return notBlank(messagingBaseUrl) ? messagingBaseUrl : DEFAULT_MESSAGING_BASE_URL;
    }

    /** {@code content.twilio.com}; a test stub of the messaging surface serves it too. */
    public String effectiveContentBaseUrl() {
        return notBlank(messagingBaseUrl) ? messagingBaseUrl : DEFAULT_CONTENT_BASE_URL;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
