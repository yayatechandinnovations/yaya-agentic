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
    private final PaymentRoutingProperties routing;

    // Concrete providers injected by type (not List<PaymentProvider>) to avoid the
    // self-inclusion cycle a @Primary PaymentProvider would create.
    public PaymentProviderRouter(DLocalPaymentProvider dlocal,
                                 StripeConnectPaymentProvider stripe,
                                 PaymentRoutingProperties routing) {
        this.dlocal = dlocal;
        this.stripe = stripe;
        this.routing = routing;
    }

    PaymentProvider select() {
        return switch (routing.effectiveProvider()) {
            case PaymentRoutingProperties.STRIPE -> stripe;
            case PaymentRoutingProperties.DLOCAL -> dlocal;
            // auto: keep dLocal as the default (seeded DR store), unless Stripe is the
            // only one actually wired for live charges.
            default -> (stripe.isConfigured() && !dlocal.isConfigured()) ? stripe : dlocal;
        };
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
