package com.yayatechandinnovations.yayasales.orders.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayasales.orders.CheckoutService.CheckoutOutcome;
import com.yayatechandinnovations.yayasales.orders.OrderView;
import com.yayatechandinnovations.yayasales.platform.Ids.OrderId;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * §5.5 idempotent-replay plumbing shared by checkout and draft-order send:
 * a stored outcome replays with a FRESH order view (status may have moved on)
 * but the same payment handle — the same hosted URL / intent, never a second
 * authorization. {@code Insufficient} is never stored: it changed nothing.
 */
@Component
class CheckoutOutcomes {

    private final ObjectMapper json;
    private final DefaultOrderService orders;

    CheckoutOutcomes(ObjectMapper json, DefaultOrderService orders) {
        this.json = json;
        this.orders = orders;
    }

    String serialize(CheckoutOutcome outcome) {
        Map<String, Object> stored = new LinkedHashMap<>();
        switch (outcome) {
            case CheckoutOutcome.HostedUrl h -> {
                stored.put("type", "HOSTED_URL");
                stored.put("orderId", h.order().id().value());
                stored.put("url", h.url());
            }
            case CheckoutOutcome.PaymentIntent pi -> {
                // Stored so a retried checkout replays the SAME intent — Stripe's
                // Idempotency-Key (the order id) made it the same intent anyway.
                stored.put("type", "PAYMENT_INTENT");
                stored.put("orderId", pi.order().id().value());
                stored.put("clientSecret", pi.clientSecret());
                stored.put("publishableKey", pi.publishableKey());
            }
            case CheckoutOutcome.Placed p -> {
                stored.put("type", "PLACED");
                stored.put("orderId", p.order().id().value());
                stored.put("instructions", p.instructions());
            }
            case CheckoutOutcome.Paid p -> {
                stored.put("type", "PAID");
                stored.put("orderId", p.order().id().value());
            }
            case CheckoutOutcome.Insufficient ignored ->
                    throw new IllegalStateException("Insufficient outcomes are not stored");
        }
        try {
            return json.writeValueAsString(stored);
        } catch (Exception e) {
            throw new IllegalStateException("unserializable checkout outcome", e);
        }
    }

    CheckoutOutcome reconstruct(String storedJson) {
        try {
            Map<?, ?> stored = json.readValue(storedJson, Map.class);
            OrderView order = orders.order(new OrderId(String.valueOf(stored.get("orderId"))))
                    .orElseThrow();
            return switch (String.valueOf(stored.get("type"))) {
                case "HOSTED_URL" -> new CheckoutOutcome.HostedUrl(order,
                        String.valueOf(stored.get("url")));
                case "PAYMENT_INTENT" -> new CheckoutOutcome.PaymentIntent(order,
                        String.valueOf(stored.get("clientSecret")),
                        String.valueOf(stored.get("publishableKey")));
                case "PLACED" -> new CheckoutOutcome.Placed(order,
                        String.valueOf(stored.get("instructions")));
                case "PAID" -> new CheckoutOutcome.Paid(order);
                default -> throw new IllegalStateException("unknown stored outcome");
            };
        } catch (ApiErrors.NotFoundException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("corrupt stored checkout outcome", e);
        }
    }
}
