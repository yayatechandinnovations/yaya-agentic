package com.yayatechandinnovations.yayaagentic.commerce.search;

import java.util.Map;

/**
 * A structured product search across a profile's catalogs. {@code text} is the
 * natural-language part (the model's "smart path"); {@code category} /
 * {@code attributes} / {@code priceMax} are the exact filters the client's facet
 * chips express (the "fast path"). See {@code ecommerce-chat-design.md} §10.2.
 */
public record ProductQuery(
        String text,
        String category,
        Map<String, String> attributes,
        Long priceMax,
        int page,
        int pageSize
) {
    public ProductQuery {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        if (page < 0) page = 0;
        if (pageSize <= 0) pageSize = 20;
    }

    public static ProductQuery of(String text) {
        return new ProductQuery(text, null, Map.of(), null, 0, 20);
    }
}
