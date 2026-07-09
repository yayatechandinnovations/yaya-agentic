package com.yayatechandinnovations.yayasales.integration.internal;

import com.yayatechandinnovations.yayasales.orders.CheckoutService.CheckoutOutcome;

import java.util.Map;

/**
 * §10.2 — one wire shape for every "money starts moving" outcome, shared by
 * cart checkout and draft-order send: the same {@code _uiHint} kind
 * ({@code checkout}) so the chat renders "tap to pay" identically for both.
 */
final class CheckoutResponses {

    private CheckoutResponses() {}

    static Map<String, Object> framed(CheckoutOutcome outcome) {
        return switch (outcome) {
            case CheckoutOutcome.HostedUrl hosted -> UiHints.framed(
                    Map.of("status", "AWAITING_PAYMENT",
                            "orderId", hosted.order().id().value(),
                            "paymentUrl", hosted.url()),
                    "checkout", Map.of("kind", "hosted_url",
                            "orderId", hosted.order().id().value(), "url", hosted.url()));
            case CheckoutOutcome.PaymentIntent intent -> UiHints.framed(
                    Map.of("status", "AWAITING_PAYMENT",
                            "orderId", intent.order().id().value(),
                            "clientSecret", intent.clientSecret(),
                            "publishableKey", intent.publishableKey()),
                    "checkout", Map.of("kind", "payment_intent",
                            "orderId", intent.order().id().value(),
                            "clientSecret", intent.clientSecret(),
                            "publishableKey", intent.publishableKey()));
            case CheckoutOutcome.Placed placed -> UiHints.framed(
                    Map.of("status", "PLACED",
                            "orderId", placed.order().id().value(),
                            "instructions", placed.instructions()),
                    "checkout", Map.of("kind", "placed",
                            "orderId", placed.order().id().value(),
                            "instructions", placed.instructions()));
            case CheckoutOutcome.Paid paid -> UiHints.framed(
                    Map.of("status", "PAID", "orderId", paid.order().id().value()),
                    "checkout", Map.of("kind", "paid", "orderId", paid.order().id().value()));
            case CheckoutOutcome.Insufficient insufficient -> UiHints.framed(
                    Map.of("status", "INSUFFICIENT_STOCK",
                            "sku", insufficient.sku().value(),
                            "available", insufficient.available()),
                    "checkout", Map.of("kind", "insufficient",
                            "sku", insufficient.sku().value(),
                            "available", insufficient.available()));
        };
    }
}
