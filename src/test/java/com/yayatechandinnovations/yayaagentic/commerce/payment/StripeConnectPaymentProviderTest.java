package com.yayatechandinnovations.yayaagentic.commerce.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.commerce.Cart;
import com.yayatechandinnovations.yayaagentic.commerce.Money;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StripeConnectPaymentProviderTest {

    private final StripeConnectPaymentProvider provider = new StripeConnectPaymentProvider(
            new StripeProperties(null, null, null, null),   // unconfigured → sandbox
            new ObjectMapper());

    private static Cart cart() {
        return new Cart(null, "USD",
                List.of(new Cart.CartLine("l1", "p1", null, "Soap", null, 2,
                        new Money(1299, "USD"), new Money(2598, "USD"))),
                new Money(2598, "USD"), Instant.EPOCH);
    }

    private static CheckoutRequest request() {
        return new CheckoutRequest(
                new Ids.TenantId("t1"), new Ids.SessionId("11112222-3333-4444-5555-666677778888"),
                "shopper", null, cart(), "USD", Map.of());
    }

    @Test
    void sandboxReturnsAPaymentIntentPricedFromTheCart() {
        CheckoutOutcome outcome = provider.createCheckout(request());

        CheckoutOutcome.PaymentIntent intent =
                assertInstanceOf(CheckoutOutcome.PaymentIntent.class, outcome);
        assertEquals(2598L, intent.amountMinor());        // authoritative cart total, not a model number
        assertEquals("USD", intent.currency());
        assertNotNull(intent.clientSecret());
        assertTrue(intent.clientSecret().contains("secret"));
        assertEquals("pk_test_sandbox", intent.publishableKey());
        assertTrue(intent.orderRef().startsWith("ord_"));
        assertEquals("stripe", provider.name());
        assertFalse(provider.isConfigured());
    }
}
