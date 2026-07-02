package com.yayatechandinnovations.yayaagentic.commerce;

import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.util.List;
import java.util.Map;

/**
 * The render + cart contract. The same payload backs two client render states
 * — a compact card (hero image, title, price, one CTA) shown inline in the
 * stream, and an expanded card (gallery, description, variants, qty stepper)
 * shown on tap. See {@code ecommerce-chat-design.md} §7.2, §10.1.
 *
 * <p>{@code price} is a {@link Money} (integer minor units) — never a float,
 * never a model-supplied number. {@code attributes} is the facet source the
 * client turns into instant filter chips (§10.2).
 */
public record Product(
        Ids.ProductId id,
        String title,
        String shortDescription,
        Money price,
        List<String> imageUrls,
        String category,
        Map<String, String> attributes,
        List<Variant> variants,
        Availability availability,
        String detailUrl
) {
    public Product {
        imageUrls = imageUrls == null ? List.of() : List.copyOf(imageUrls);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        variants = variants == null ? List.of() : List.copyOf(variants);
    }

    /** A purchasable variation (size/scent/…); each carries its own price/sku/stock. */
    public record Variant(
            String variantId,
            String label,
            Money price,
            String sku,
            Availability availability
    ) {}
}
