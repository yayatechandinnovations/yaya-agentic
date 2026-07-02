package com.yayatechandinnovations.yayaagentic.commerce;

import java.util.Map;

/**
 * Maps a merchant's product JSON onto the normalized {@link Product} contract
 * the client renderers expect — the commerce analog of
 * {@code HttpToolSpec.ResponseProjection}. Configuration is pure admin-console
 * data (no code, no deploy); see {@code ecommerce-chat-design.md} §7.1, §8.
 *
 * <p>{@code itemsPath} points at the array of products in the merchant response;
 * {@code fieldPaths} maps each {@link Product} field name (e.g. {@code "title"},
 * {@code "price.amountMinor"}, {@code "attributes"}) to a JSON path evaluated
 * against a single item. Price is always read as integer minor units — a
 * projection that yields a float is rejected, keeping constraint #1 intact.</p>
 */
public record ProductProjection(
        String itemsPath,
        Map<String, String> fieldPaths
) {
    public ProductProjection {
        fieldPaths = fieldPaths == null ? Map.of() : Map.copyOf(fieldPaths);
    }
}
