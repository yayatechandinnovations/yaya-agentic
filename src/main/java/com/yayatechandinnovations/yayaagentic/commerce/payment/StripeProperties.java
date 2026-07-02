package com.yayatechandinnovations.yayaagentic.commerce.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Stripe (Connect) configuration, bound from {@code yaya.agentic.commerce.stripe.*}.
 * All keys are nullable so dev / test boot without Stripe wired — when unconfigured
 * the provider runs in {@code sandbox} mode and fabricates a deterministic
 * {@code PaymentIntent} so the {@code payment_intent} checkout path is exercisable
 * with no external dependency (the same degrade-gracefully pattern as
 * {@link DLocalProperties}). Real test keys ({@code sk_test_…} / {@code pk_test_…})
 * switch it to live Stripe API calls that a test card can actually confirm.
 *
 * <p>Stripe is the Connect-model PSP for markets it supports (US/EU/…); it carries
 * card data, PCI, KYC, settlement, and chargeback liability — the framework never
 * sits in the flow of funds (ecommerce-chat-design.md §11, open Q#7).</p>
 *
 * @param secretKey       Stripe secret key ({@code sk_test_…} / {@code sk_live_…}); never logged
 * @param publishableKey  Stripe publishable key ({@code pk_…}); safe to hand the client
 * @param apiBase         Stripe API host (default {@link #DEFAULT_API_BASE})
 */
@ConfigurationProperties(prefix = "yaya.agentic.commerce.stripe")
public record StripeProperties(
        String secretKey,
        String publishableKey,
        String apiBase
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
