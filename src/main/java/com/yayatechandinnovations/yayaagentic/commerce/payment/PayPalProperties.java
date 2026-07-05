package com.yayatechandinnovations.yayaagentic.commerce.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PayPal (Commerce Platform / Orders v2) configuration, bound from
 * {@code yaya.agentic.commerce.paypal.*}. All values are nullable so dev / test
 * boot without PayPal wired — when unconfigured the provider runs in
 * {@code sandbox} mode and fabricates a deterministic approval URL so the
 * hosted-URL checkout path is exercisable with no external dependency (the same
 * degrade-gracefully pattern as {@link DLocalProperties}). Real client
 * credentials switch it to live Orders v2 API calls.
 *
 * <p>PayPal covers the Dominican Republic (and ~200 markets) and, via the
 * Commerce Platform multiparty model, supports connected merchants + a platform
 * fee. It carries card data, PCI, KYC, settlement, and chargeback liability — the
 * framework never sits in the flow of funds. See
 * {@code commerce-redesign-authoring-rendering-paypal.md} Pillar C.</p>
 *
 * @param clientId             PayPal REST app client id
 * @param clientSecret         PayPal REST app secret; never logged
 * @param apiBase              PayPal REST host (default {@link #DEFAULT_API_BASE})
 * @param sandboxUrl           base URL for the fabricated approval link in sandbox mode
 * @param partnerAttributionId BN code for Commerce Platform attribution (nullable)
 * @param webhookId            PayPal webhook id used to verify inbound notifications (nullable)
 */
@ConfigurationProperties(prefix = "yaya.agentic.commerce.paypal")
public record PayPalProperties(
        String clientId,
        String clientSecret,
        String apiBase,
        String sandboxUrl,
        String partnerAttributionId,
        String webhookId
) {

    public static final String DEFAULT_API_BASE = "https://api-m.paypal.com";
    public static final String DEFAULT_SANDBOX_URL = "https://sandbox.paypal.example/checkoutnow";

    /** Live mode requires a client id + secret (to mint an OAuth token); else sandbox. */
    public boolean isConfigured() {
        return notBlank(clientId) && notBlank(clientSecret);
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
