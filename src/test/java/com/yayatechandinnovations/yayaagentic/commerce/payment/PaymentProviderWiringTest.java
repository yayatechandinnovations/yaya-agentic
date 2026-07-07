package com.yayatechandinnovations.yayaagentic.commerce.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Locks in the payment-provider wiring without booting the full app (no Docker /
 * Testcontainers). It registers the REAL {@code @Component}/{@code @Primary}
 * provider classes and asserts that:
 * <ol>
 *   <li>injecting the {@link PaymentProvider} SPI is unambiguous despite three
 *       candidate beans (dLocal, Stripe, router) — the {@code @Primary} router wins;</li>
 *   <li>the router honors {@code yaya.agentic.commerce.payment.provider}.</li>
 * </ol>
 * If the {@code @Primary} annotation were dropped, {@code getBean(PaymentProvider.class)}
 * would throw {@code NoUniqueBeanDefinitionException} and this test would fail.
 */
class PaymentProviderWiringTest {

    private static AnnotationConfigApplicationContext context(String provider) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        ctx.registerBean(DLocalProperties.class,
                () -> new DLocalProperties(null, null, null, null, null));   // sandbox
        ctx.registerBean(StripeProperties.class,
                () -> new StripeProperties(null, null, null, null));               // sandbox
        ctx.registerBean(PayPalProperties.class,
                () -> new PayPalProperties(null, null, null, null, null, null)); // sandbox
        ctx.registerBean(PaymentRoutingProperties.class,
                () -> new PaymentRoutingProperties(provider));
        ctx.register(DLocalPaymentProvider.class,
                StripeConnectPaymentProvider.class,
                PayPalPaymentProvider.class,
                PaymentProviderRouter.class);
        ctx.refresh();
        return ctx;
    }

    @Test
    void primaryPaymentProviderIsTheRouterAndAutoSelectsDlocal() {
        try (AnnotationConfigApplicationContext ctx = context("auto")) {
            PaymentProvider primary = ctx.getBean(PaymentProvider.class); // no ambiguity → @Primary
            assertInstanceOf(PaymentProviderRouter.class, primary);
            assertEquals("dlocal", primary.name());                       // auto → dLocal
        }
    }

    @Test
    void routerSelectsStripeWhenConfigured() {
        try (AnnotationConfigApplicationContext ctx = context("stripe")) {
            PaymentProvider primary = ctx.getBean(PaymentProvider.class);
            assertInstanceOf(PaymentProviderRouter.class, primary);
            assertEquals("stripe", primary.name());
        }
    }

    @Test
    void routerSelectsPaypalWhenConfigured() {
        try (AnnotationConfigApplicationContext ctx = context("paypal")) {
            PaymentProvider primary = ctx.getBean(PaymentProvider.class);
            assertInstanceOf(PaymentProviderRouter.class, primary);
            assertEquals("paypal", primary.name());
        }
    }

    @Test
    void allConcreteProvidersRemainAddressableByType() {
        try (AnnotationConfigApplicationContext ctx = context("auto")) {
            assertEquals("dlocal", ctx.getBean(DLocalPaymentProvider.class).name());
            assertEquals("stripe", ctx.getBean(StripeConnectPaymentProvider.class).name());
            assertEquals("paypal", ctx.getBean(PayPalPaymentProvider.class).name());
        }
    }
}
