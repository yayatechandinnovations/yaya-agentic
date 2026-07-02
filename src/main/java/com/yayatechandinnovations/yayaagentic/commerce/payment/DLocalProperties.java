package com.yayatechandinnovations.yayaagentic.commerce.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * dLocal for Platforms configuration, bound from
 * {@code yaya.agentic.commerce.dlocal.*}. All credentials are nullable so dev /
 * test boot without dLocal wired — when unconfigured the provider runs in
 * {@code sandbox} mode and returns a deterministic hosted-checkout URL (the same
 * degrade-gracefully pattern as the Twilio channel). Real credentials switch it
 * to live API calls. See {@code ecommerce-chat-design.md} §11, open Q#7.
 *
 * @param apiBase    dLocal API host (default {@link #DEFAULT_API_BASE})
 * @param xLogin     dLocal {@code X-Login}
 * @param transKey   dLocal {@code X-Trans-Key}
 * @param secretKey  dLocal HMAC secret used to sign requests; never logged
 * @param sandboxUrl base URL for the fabricated hosted-checkout link in sandbox mode
 */
@ConfigurationProperties(prefix = "yaya.agentic.commerce.dlocal")
public record DLocalProperties(
        String apiBase,
        String xLogin,
        String transKey,
        String secretKey,
        String sandboxUrl
) {

    public static final String DEFAULT_API_BASE = "https://api.dlocal.com";
    public static final String DEFAULT_SANDBOX_URL = "https://sandbox.dlocal.example/checkout";

    /** Live mode requires all three credentials; otherwise we stay in sandbox. */
    public boolean isConfigured() {
        return notBlank(xLogin) && notBlank(transKey) && notBlank(secretKey);
    }

    public String effectiveApiBase() {
        return notBlank(apiBase) ? apiBase : DEFAULT_API_BASE;
    }

    public String effectiveSandboxUrl() {
        return notBlank(sandboxUrl) ? sandboxUrl : DEFAULT_SANDBOX_URL;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
