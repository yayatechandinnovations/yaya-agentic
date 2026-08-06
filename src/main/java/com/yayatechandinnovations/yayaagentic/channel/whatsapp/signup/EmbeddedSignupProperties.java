package com.yayatechandinnovations.yayaagentic.channel.whatsapp.signup;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Meta Embedded Signup configuration, bound from
 * {@code yaya.agentic.channels.whatsapp.embedded-signup.*}. All values are
 * deploy-time platform config — never per-tenant, never persisted.
 *
 * <p>{@code metaAppId} / {@code metaConfigurationId} /
 * {@code partnerSolutionId} are public identifiers (they appear in the
 * browser's Facebook SDK calls) — the secrets stay on the Twilio side.
 * Startup fails fast via {@link EmbeddedSignupStartupValidator} when the
 * feature is enabled but required values are missing.
 *
 * @param enabled             feature flag ({@code WHATSAPP_ONBOARDING_ENABLED})
 * @param metaAppId           Meta App id hosting the Embedded Signup flow
 * @param metaConfigurationId Meta Embedded Signup configuration id
 * @param partnerSolutionId   Twilio Partner Solution id linking the Meta app
 *                            to Twilio's Tech Provider integration; optional
 *                            when the Meta configuration already embeds it
 */
@ConfigurationProperties(prefix = "yaya.agentic.channels.whatsapp.embedded-signup")
public record EmbeddedSignupProperties(
        boolean enabled,
        String metaAppId,
        String metaConfigurationId,
        String partnerSolutionId
) {

    public boolean isConfigured() {
        return notBlank(metaAppId) && notBlank(metaConfigurationId);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
