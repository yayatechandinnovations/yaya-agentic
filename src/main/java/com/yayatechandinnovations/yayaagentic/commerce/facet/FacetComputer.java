package com.yayatechandinnovations.yayaagentic.commerce.facet;

import com.yayatechandinnovations.yayaagentic.commerce.Product;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Derives filter facets from a returned product set — the deterministic "fast
 * path" of the filtering UX (§10.2). Categorical facets come from each product's
 * {@code category} and {@code attributes}; a price facet comes from fixed bands.
 * Only dimensions that can actually split the set (≥2 non-empty values/bands) are
 * emitted, so the client never renders a useless single-value chip group.
 */
@Component
public class FacetComputer {

    static final String CATEGORY_KEY = "category";
    static final String PRICE_KEY = "price";

    /** Fixed price bands in minor units; open-ended at both extremes. */
    private static final List<PriceBand> PRICE_BANDS = List.of(
            new PriceBand("< $10", null, 1000L),
            new PriceBand("$10–$20", 1000L, 2000L),
            new PriceBand("$20–$50", 2000L, 5000L),
            new PriceBand("$50+", 5000L, null));

    public List<Facet> compute(List<Product> products) {
        if (products == null || products.isEmpty()) return List.of();

        List<Facet> facets = new ArrayList<>();

        categorical(CATEGORY_KEY, "Category",
                products.stream().map(Product::category).toList()).ifPresent(facets::add);

        for (String attrKey : attributeKeys(products)) {
            categorical(attrKey, humanize(attrKey),
                    products.stream().map(p -> p.attributes().get(attrKey)).toList())
                    .ifPresent(facets::add);
        }

        priceFacet(products).ifPresent(facets::add);
        return facets;
    }

    // --- categorical -------------------------------------------------------

    private java.util.Optional<Facet> categorical(String key, String label, List<String> rawValues) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String v : rawValues) {
            if (v == null || v.isBlank()) continue;
            counts.merge(v, 1, Integer::sum);
        }
        if (counts.size() < 2) return java.util.Optional.empty();  // can't split the set → skip

        List<Facet.Value> values = counts.entrySet().stream()
                .map(e -> new Facet.Value(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingInt(Facet.Value::count).reversed()
                        .thenComparing(Facet.Value::v))
                .toList();
        return java.util.Optional.of(Facet.categorical(key, label, values));
    }

    /** Attribute keys sorted for determinism, so facet order is stable across calls. */
    private java.util.SortedSet<String> attributeKeys(List<Product> products) {
        java.util.TreeSet<String> keys = new java.util.TreeSet<>();
        for (Product p : products) keys.addAll(p.attributes().keySet());
        return keys;
    }

    // --- price -------------------------------------------------------------

    private java.util.Optional<Facet> priceFacet(List<Product> products) {
        Map<String, Integer> counts = new TreeMap<>();
        List<Facet.Bucket> buckets = new ArrayList<>();
        for (PriceBand band : PRICE_BANDS) {
            int count = (int) products.stream()
                    .filter(p -> p.price() != null && band.contains(p.price().amountMinor()))
                    .count();
            if (count > 0) {
                buckets.add(new Facet.Bucket(band.label(), band.min(), band.max(), count));
            }
        }
        if (buckets.size() < 2) return java.util.Optional.empty();
        return java.util.Optional.of(Facet.price(PRICE_KEY, "Price", buckets));
    }

    private static String humanize(String key) {
        String cleaned = key.replace('_', ' ').replace('-', ' ').trim();
        if (cleaned.isEmpty()) return key;
        return Character.toUpperCase(cleaned.charAt(0)) + cleaned.substring(1);
    }

    private record PriceBand(String label, Long min, Long max) {
        boolean contains(long amountMinor) {
            return (min == null || amountMinor >= min) && (max == null || amountMinor < max);
        }
    }
}
