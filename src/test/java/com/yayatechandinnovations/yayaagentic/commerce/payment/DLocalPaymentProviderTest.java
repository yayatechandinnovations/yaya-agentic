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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DLocalPaymentProviderTest {

    private final DLocalPaymentProvider provider = new DLocalPaymentProvider(
            new DLocalProperties(null, null, null, null, null),   // unconfigured → sandbox
            new ObjectMapper());

    @Test
    void sandboxReturnsHostedUrlPricedFromTheCart() {
        Cart cart = new Cart(null, "USD",
                List.of(new Cart.CartLine("l1", "p1", null, "Soap", null, 2,
                        new Money(1299, "USD"), new Money(2598, "USD"))),
                new Money(2598, "USD"), Instant.EPOCH);

        CheckoutOutcome outcome = provider.createCheckout(new CheckoutRequest(
                new Ids.TenantId("t1"), new Ids.SessionId("11112222-3333-4444-5555-666677778888"),
                "shopper", null, cart, "USD", Map.of()));

        CheckoutOutcome.HostedUrl hosted = assertInstanceOf(CheckoutOutcome.HostedUrl.class, outcome);
        assertEquals(2598L, hosted.amountMinor());        // authoritative cart total, not a model number
        assertEquals("USD", hosted.currency());
        assertTrue(hosted.url().contains("amount=2598"));
        assertTrue(hosted.orderRef().startsWith("ord_"));
        assertEquals("dlocal", provider.name());
    }
}
