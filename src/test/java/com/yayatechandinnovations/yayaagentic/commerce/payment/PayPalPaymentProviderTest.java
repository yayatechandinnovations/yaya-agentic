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

class PayPalPaymentProviderTest {

    private final PayPalPaymentProvider provider = new PayPalPaymentProvider(
            new PayPalProperties(null, null, null, null, null, null),   // unconfigured → sandbox
            new ObjectMapper());

    @Test
    void sandboxReturnsApprovalUrlPricedFromTheCart() {
        Cart cart = new Cart(null, "DOP",
                List.of(new Cart.CartLine("l1", "p1", null, "Soap", null, 2,
                        new Money(899, "DOP"), new Money(1798, "DOP"))),
                new Money(1798, "DOP"), Instant.EPOCH);

        CheckoutOutcome outcome = provider.createCheckout(new CheckoutRequest(
                new Ids.TenantId("t1"), new Ids.SessionId("11112222-3333-4444-5555-666677778888"),
                "shopper", null, cart, "DOP", Map.of()));

        // PayPal's approval URL maps onto HostedUrl — no new sealed variant, client unchanged.
        CheckoutOutcome.HostedUrl hosted = assertInstanceOf(CheckoutOutcome.HostedUrl.class, outcome);
        assertEquals(1798L, hosted.amountMinor());        // authoritative cart total, not a model number
        assertEquals("DOP", hosted.currency());
        assertTrue(hosted.url().contains("amount=1798"));
        assertTrue(hosted.orderRef().startsWith("ppl_"));
        assertEquals("paypal", provider.name());
    }
}
