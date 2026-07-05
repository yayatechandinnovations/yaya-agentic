package com.yayatechandinnovations.yayaagentic.commerce.payment;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Selects the {@link PaymentProvider} for a checkout — dLocal (hosted-URL) vs
 * Stripe (payment-intent drop-in) — by config ({@link PaymentRoutingProperties}).
 * It is the {@code @Primary} {@code PaymentProvider}, so {@code CheckoutToolHandler}
 * injects this and stays provider-agnostic; the concrete providers remain plain
 * (non-primary) beans it delegates to. Mirrors the {@code RecorderRouter} pattern.
 *
 * <p>Selection is config-only (not per-request) so {@link #name()} — called for the
 * order-audit row without a request — always matches the provider {@link #createCheckout}
 * would pick. Currency/region-aware routing is a future refinement (§11, open Q#7).</p>
 */
@Component
@Primary
public class PaymentProviderRouter implements PaymentProvider {

    private final DLocalPaymentProvider dlocal;
    private final StripeConnectPaymentProvider stripe;
    private final PayPalPaymentProvider paypal;
    private final PaymentRoutingProperties routing;

    // Concrete providers injected by type (not List<PaymentProvider>) to avoid the
    // self-inclusion cycle a @Primary PaymentProvider would create.
    public PaymentProviderRouter(DLocalPaymentProvider dlocal,
                                 StripeConnectPaymentProvider stripe,
                                 PayPalPaymentProvider paypal,
                                 PaymentRoutingProperties routing) {
        this.dlocal = dlocal;
        this.stripe = stripe;
        this.paypal = paypal;
        this.routing = routing;
    }

    /** The framework-default provider (no per-request preference). */
    PaymentProvider select() {
        return resolve(null);
    }

    /**
     * Resolve the provider for an explicit preference (a profile's
     * {@code CommerceConfig.paymentProvider}); a blank/null preference falls back
     * to the framework default ({@link PaymentRoutingProperties}). This is the
     * seam that makes provider choice per-profile, not just global — the checkout
     * handler resolves once and uses the same instance for {@code createCheckout}
     * and the audit row's {@code name()}. Pillar C §C.2.
     */
    public PaymentProvider resolve(String providerPref) {
        String pref = (providerPref == null || providerPref.isBlank())
                ? routing.effectiveProvider()
                : providerPref.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (pref) {
            case PaymentRoutingProperties.STRIPE -> stripe;
            case PaymentRoutingProperties.DLOCAL -> dlocal;
            case PaymentRoutingProperties.PAYPAL -> paypal;
            default -> autoSelect();
        };
    }

    // auto: keep dLocal as the default (seeded DR store), unless exactly one of the
    // redirect-based PSPs (PayPal/Stripe) is the only one wired live.
    private PaymentProvider autoSelect() {
        if (paypal.isConfigured() && !dlocal.isConfigured() && !stripe.isConfigured()) return paypal;
        if (stripe.isConfigured() && !dlocal.isConfigured() && !paypal.isConfigured()) return stripe;
        return dlocal;
    }

    @Override
    public String name() {
        return select().name();
    }

    @Override
    public CheckoutOutcome createCheckout(CheckoutRequest request) {
        return select().createCheckout(request);
    }
}
