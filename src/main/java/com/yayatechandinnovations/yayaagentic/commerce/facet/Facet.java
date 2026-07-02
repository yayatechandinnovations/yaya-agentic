package com.yayatechandinnovations.yayaagentic.commerce.facet;

import java.util.List;

/**
 * A filterable dimension derived server-side from a returned product set, shipped
 * inside the {@code product_catalog} payload so the client can filter the
 * already-loaded items with zero latency and zero model round-trip (§10.2). A
 * categorical facet (category, ingredient, form) carries {@code values}; a price
 * facet carries {@code buckets}. See {@code ecommerce-chat-design.md} §10.2.
 */
public record Facet(
        String key,
        String label,
        List<Value> values,
        List<Bucket> buckets
) {
    public Facet {
        values = values == null ? List.of() : List.copyOf(values);
        buckets = buckets == null ? List.of() : List.copyOf(buckets);
    }

    public static Facet categorical(String key, String label, List<Value> values) {
        return new Facet(key, label, values, List.of());
    }

    public static Facet price(String key, String label, List<Bucket> buckets) {
        return new Facet(key, label, List.of(), buckets);
    }

    /** One value of a categorical facet plus how many products carry it. */
    public record Value(String v, int count) {}

    /** A price band in integer minor units. {@code min}/{@code max} nullable = open-ended. */
    public record Bucket(String label, Long min, Long max, int count) {}
}
