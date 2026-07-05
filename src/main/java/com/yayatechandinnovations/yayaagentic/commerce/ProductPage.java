package com.yayatechandinnovations.yayaagentic.commerce;

import java.util.List;

/** One page of managed products for the admin table (server-side pagination). */
public record ProductPage(
        List<ManagedProduct> items,
        int page,
        int size,
        long total
) {
    public ProductPage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
