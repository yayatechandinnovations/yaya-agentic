package com.yayatechandinnovations.yayaagentic.commerce;

/**
 * Admin-side paged/filtered/sorted query over a catalog's managed products (the
 * spreadsheet-like product table). {@code status} defaults to {@code ACTIVE};
 * pass {@code ARCHIVED} or {@code ALL} to see retired rows. {@code sort} is one
 * of {@code SORT_ORDER|TITLE|PRICE|UPDATED}. See
 * {@code commerce-redesign-authoring-rendering-paypal.md} Pillar A.
 */
public record ProductListQuery(
        String text,          // keyword over title / product_id / short description
        String category,
        String availability,
        String status,        // ACTIVE (default) | ARCHIVED | ALL
        String sort,          // SORT_ORDER (default) | TITLE | PRICE | UPDATED
        int page,
        int size
) {
    public ProductListQuery {
        status = (status == null || status.isBlank()) ? ManagedProduct.ACTIVE : status.toUpperCase();
        sort = (sort == null || sort.isBlank()) ? "SORT_ORDER" : sort.toUpperCase();
        if (page < 0) page = 0;
        if (size <= 0) size = 25;
        if (size > 200) size = 200;   // guard against a client asking for the whole table at once
    }
}
