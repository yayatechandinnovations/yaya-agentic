package com.yayatechandinnovations.yayaagentic.commerce;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.PathNotFoundException;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps a merchant's product JSON onto the normalized {@link Product} contract
 * using a {@link ProductProjection}'s JsonPath field map — the commerce analog of
 * the HTTP tool {@code ResponseProjection}. Price is always read as an integer
 * (minor units); a non-integer price is rejected, so constraint #1 ("the model —
 * and now the merchant's own JSON quirks — never smuggle a float price") holds at
 * the boundary. See {@code ecommerce-chat-design.md} §7.1, §8.
 *
 * <p>Recognized field keys in {@link ProductProjection#fieldPaths()}: {@code id},
 * {@code title}, {@code shortDescription}, {@code category}, {@code detailUrl},
 * {@code availability}, {@code price.amountMinor}, {@code price.currency},
 * {@code imageUrls}, {@code attributes}. Each value is a JsonPath evaluated
 * against a single item. Missing optional paths default gracefully.</p>
 */
@Component
public class ProductProjector {

    private static final Configuration CFG = Configuration.defaultConfiguration()
            .addOptions(Option.SUPPRESS_EXCEPTIONS);

    /**
     * @param responseBody    the merchant's raw JSON response
     * @param projection      itemsPath + per-field JsonPaths
     * @param defaultCurrency currency used when the projection has no price.currency path
     */
    public List<Product> project(String responseBody, ProductProjection projection, String defaultCurrency) {
        List<Object> items = readItems(responseBody, projection.itemsPath());
        List<Product> out = new ArrayList<>(items.size());
        for (Object item : items) {
            out.add(projectOne(JsonPath.parse(item, CFG), projection.fieldPaths(), defaultCurrency));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Object> readItems(String body, String itemsPath) {
        if (itemsPath == null || itemsPath.isBlank() || "$".equals(itemsPath)) {
            Object root = JsonPath.parse(body, CFG).json();
            return root instanceof List<?> list ? new ArrayList<>((List<Object>) list) : List.of(root);
        }
        Object read = JsonPath.parse(body, CFG).read(itemsPath);
        if (read instanceof List<?> list) return new ArrayList<>((List<Object>) list);
        return read == null ? List.of() : List.of(read);
    }

    private Product projectOne(DocumentContext item, Map<String, String> paths, String defaultCurrency) {
        String currency = orDefault(str(item, paths.get("price.currency")), defaultCurrency);
        long amountMinor = requireLong(item, paths.get("price.amountMinor"));

        return new Product(
                new Ids.ProductId(orDefault(str(item, paths.get("id")), "")),
                orDefault(str(item, paths.get("title")), ""),
                orDefault(str(item, paths.get("shortDescription")), ""),
                new Money(amountMinor, currency),
                stringList(item, paths.get("imageUrls")),
                str(item, paths.get("category")),
                stringMap(item, paths.get("attributes")),
                List.of(),                                    // variant projection lands in Phase 2B
                availability(str(item, paths.get("availability"))),
                str(item, paths.get("detailUrl")));
    }

    // --- typed readers -----------------------------------------------------

    private static String str(DocumentContext item, String path) {
        if (path == null) return null;
        Object v = read(item, path);
        return v == null ? null : String.valueOf(v);
    }

    private static long requireLong(DocumentContext item, String path) {
        Object v = path == null ? null : read(item, path);
        if (v == null) {
            throw new IllegalArgumentException("product price path missing or null: " + path);
        }
        if (v instanceof Number n) {
            if (n.doubleValue() != Math.rint(n.doubleValue())) {
                throw new IllegalArgumentException("price must be integer minor units, got: " + v);
            }
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("price is not integer minor units: " + v, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(DocumentContext item, String path) {
        if (path == null) return List.of();
        Object v = read(item, path);
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object o : list) if (o != null) out.add(String.valueOf(o));
            return out;
        }
        return v == null ? List.of() : List.of(String.valueOf(v));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> stringMap(DocumentContext item, String path) {
        if (path == null) return Map.of();
        Object v = read(item, path);
        if (!(v instanceof Map<?, ?> map)) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        ((Map<String, Object>) map).forEach((k, val) -> {
            if (val != null) out.put(k, String.valueOf(val));
        });
        return out;
    }

    private static Object read(DocumentContext item, String path) {
        try {
            return item.read(path);
        } catch (PathNotFoundException e) {
            return null;
        }
    }

    private static Availability availability(String raw) {
        if (raw == null || raw.isBlank()) return Availability.IN_STOCK;
        try {
            return Availability.valueOf(raw.trim().toUpperCase().replace(' ', '_'));
        } catch (IllegalArgumentException e) {
            return Availability.IN_STOCK;
        }
    }

    private static String orDefault(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }
}
