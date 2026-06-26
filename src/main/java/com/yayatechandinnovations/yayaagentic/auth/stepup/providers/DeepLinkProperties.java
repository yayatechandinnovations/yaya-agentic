package com.yayatechandinnovations.yayaagentic.auth.stepup.providers;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Tunables for {@link DeepLinkProvider}. Configured under
 * {@code yaya.agentic.auth.step-up.deep-link}.
 *
 * <p>The secret signs and verifies the deep-link JWT AND authenticates the
 * tenant's callback POST. Platform-owned; the tenant's web app receives it
 * via secure operator handoff (admin console / runbook) and treats it as a
 * regular bearer token.
 *
 * <p>See {@code whatsapp-channel-auth-design.md} §6.3 + §10.3.
 */
@Component
@ConfigurationProperties("yaya.agentic.auth.step-up.deep-link")
public class DeepLinkProperties {

    /** Base64-encoded HMAC secret. Empty disables the DeepLink provider. */
    private String secret = "";

    /**
     * URL the bot points the user at; the tenant's web app handles the
     * landing page, runs OIDC, then POSTs back to the callback. The provider
     * appends {@code ?token=<jwt>} to the URL. Empty disables the provider.
     */
    private String landingUrl = "";

    /** Lifetime of the issued JWT — short by design. */
    private Duration jwtTtl = Duration.ofMinutes(5);

    public String getSecret() { return secret; }
    public String getLandingUrl() { return landingUrl; }
    public Duration getJwtTtl() { return jwtTtl; }

    public void setSecret(String secret) {
        this.secret = secret == null ? "" : secret;
    }
    public void setLandingUrl(String url) {
        this.landingUrl = url == null ? "" : url;
    }
    public void setJwtTtl(Duration ttl) {
        this.jwtTtl = ttl == null || ttl.isZero() || ttl.isNegative()
                ? Duration.ofMinutes(5) : ttl;
    }

    public boolean isConfigured() {
        return !secret.isBlank() && !landingUrl.isBlank();
    }
}
