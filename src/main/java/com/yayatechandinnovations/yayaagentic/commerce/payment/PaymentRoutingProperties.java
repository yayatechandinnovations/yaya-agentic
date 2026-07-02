package com.yayatechandinnovations.yayaagentic.commerce.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Locale;

/**
 * Which {@link PaymentProvider} the {@link PaymentProviderRouter} picks, bound
 * from {@code yaya.agentic.commerce.payment.*}. The {@code PaymentProvider} SPI
 * is per-tenant/per-region and swappable (ecommerce-chat-design.md §11, open Q#7);
 * this is the framework-wide default knob.
 *
 * <ul>
 *   <li>{@code auto} (default) — dLocal, preserving the seeded DR-store behavior;
 *       switches to Stripe only when Stripe is explicitly wired AND dLocal is not.</li>
 *   <li>{@code dlocal} — force dLocal (hosted-URL flow).</li>
 *   <li>{@code stripe} — force Stripe (payment-intent drop-in flow). Set this to
 *       exercise the Stripe path (sandbox with no keys, live with test keys).</li>
 * </ul>
 *
 * @param provider {@code auto} | {@code dlocal} | {@code stripe} (case-insensitive)
 */
@ConfigurationProperties(prefix = "yaya.agentic.commerce.payment")
public record PaymentRoutingProperties(String provider) {

    public static final String AUTO = "auto";
    public static final String DLOCAL = "dlocal";
    public static final String STRIPE = "stripe";

    public String effectiveProvider() {
        return provider == null || provider.isBlank()
                ? AUTO
                : provider.trim().toLowerCase(Locale.ROOT);
    }
}
