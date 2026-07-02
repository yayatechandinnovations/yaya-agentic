package com.yayatechandinnovations.yayaagentic.commerce.search;

import com.yayatechandinnovations.yayaagentic.commerce.Product;
import com.yayatechandinnovations.yayaagentic.commerce.facet.Facet;

import java.util.List;

/**
 * One page of products plus the facets derived from the whole matched set (not
 * just the page), so the client's filter chips reflect every result. Feeds the
 * {@code product_catalog} UiHint. See {@code ecommerce-chat-design.md} §10.2.
 */
public record ProductSearchResult(
        List<Product> items,
        List<Facet> facets,
        int page,
        boolean hasMore,
        int total,
        String echo
) {
    public ProductSearchResult {
        items = items == null ? List.of() : List.copyOf(items);
        facets = facets == null ? List.of() : List.copyOf(facets);
    }

    public static ProductSearchResult empty(String echo) {
        return new ProductSearchResult(List.of(), List.of(), 0, false, 0, echo);
    }
}
