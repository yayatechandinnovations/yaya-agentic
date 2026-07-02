package com.yayatechandinnovations.yayaagentic.commerce;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.commerce.facet.Facet;
import com.yayatechandinnovations.yayaagentic.commerce.payment.CheckoutOutcome;
import com.yayatechandinnovations.yayaagentic.commerce.search.ProductSearchResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the render payloads for the three commerce {@code UiHint} kinds
 * ({@code product_catalog}, {@code product_card}, {@code cart}). Kept as plain
 * {@code Map}s so a Bean tool handler can enqueue them via {@code PendingUiHints}
 * without depending on the engine's event types. The client renders these as
 * chrome — the model never enumerates them (non-negotiable #8). See
 * {@code ecommerce-chat-design.md} §2, §10.
 */
@Component
public class CommercePayloads {

    public static final String PRODUCT_CATALOG = "product_catalog";
    public static final String PRODUCT_CARD = "product_card";
    public static final String CART = "cart";
    public static final String CHECKOUT_RESULT = "checkout_result";

    private final ObjectMapper json;

    public CommercePayloads(ObjectMapper json) {
        this.json = json;
    }

    /** Grid/carousel of compact cards + facets that travel for instant local filtering (§10.2). */
    public Map<String, Object> catalog(ProductSearchResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("items", result.items().stream().map(this::asMap).toList());
        payload.put("facets", result.facets().stream().map((Facet f) -> asMap(f)).toList());
        payload.put("page", Map.of("index", result.page(), "hasMore", result.hasMore()));
        payload.put("total", result.total());
        payload.put("query", Map.of("echo", result.echo() == null ? "" : result.echo()));
        return payload;
    }

    /** A single expanded product card. */
    public Map<String, Object> productCard(Product product) {
        return asMap(product);
    }

    /** The editable cart widget + badge source. */
    public Map<String, Object> cart(Cart cart) {
        return asMap(cart);
    }

    /**
     * The checkout hand-off: how the client completes payment. Carries the
     * order ref + authoritative amount and exactly one of {@code url} (hosted
     * page), {@code clientSecret}+{@code publishableKey} (PSP drop-in), or a
     * terminal {@code status} (order placed). Never any card data.
     */
    public Map<String, Object> checkoutResult(CheckoutOutcome outcome) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderRef", outcome.orderRef());
        payload.put("amountMinor", outcome.amountMinor());
        payload.put("currency", outcome.currency());
        switch (outcome) {
            case CheckoutOutcome.HostedUrl h -> {
                payload.put("mode", "hosted_url");
                payload.put("url", h.url());
            }
            case CheckoutOutcome.PaymentIntent p -> {
                payload.put("mode", "payment_intent");
                payload.put("clientSecret", p.clientSecret());
                payload.put("publishableKey", p.publishableKey());
            }
            case CheckoutOutcome.OrderPlaced o -> {
                payload.put("mode", "order_placed");
                payload.put("status", o.status());
            }
        }
        return payload;
    }

    private Map<String, Object> asMap(Object record) {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = json.convertValue(record, Map.class);
        return map;
    }
}
