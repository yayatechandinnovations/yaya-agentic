package com.yayatechandinnovations.yayasales.payments.internal.providers;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Stripe (Connect) configuration, bound from {@code yaya.sales.payments.stripe.*}.
 * All keys are nullable so dev / test boot without Stripe wired — when unconfigured
 * the provider runs in {@code sandbox} mode and fabricates a deterministic
 * {@code Intent} so the payment-intent checkout path is exercisable with no
 * external dependency (the same degrade-gracefully pattern as
 * {@link DLocalProperties}). Real test keys ({@code sk_test_…} / {@code pk_test_…})
 * switch it to live Stripe API calls that a test card can actually confirm.
 *
 * <p>Stripe carries card data, PCI, KYC, settlement, and chargeback liability —
 * yaya-sales never sits in the flow of funds (design §8.3, §15).</p>
 *
 * @param secretKey       Stripe secret key ({@code sk_test_…} / {@code sk_live_…}); never logged
 * @param publishableKey  Stripe publishable key ({@code pk_…}); safe to hand the client
 * @param apiBase         Stripe API host (default {@link #DEFAULT_API_BASE})
 */
@ConfigurationProperties(prefix = "yaya.sales.payments.stripe")
public record StripeProperties(
        String secretKey,
        String publishableKey,
        String apiBase,
        String webhookSigningSecret   // whsec_… — verifies inbound Stripe webhooks
) {

    public static final String DEFAULT_API_BASE = "https://api.stripe.com";

    /** Live mode requires a secret key (to create the intent) and a publishable
     *  key (for the client drop-in); otherwise we stay in sandbox. */
    public boolean isConfigured() {
        return notBlank(secretKey) && notBlank(publishableKey);
    }

    public String effectiveApiBase() {
        return notBlank(apiBase) ? apiBase : DEFAULT_API_BASE;
    }

    /** In sandbox the client still needs *a* publishable key value; a clearly-fake
     *  placeholder makes it obvious the intent won't confirm against real Stripe. */
    public String effectivePublishableKey() {
        return notBlank(publishableKey) ? publishableKey : "pk_test_sandbox";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
