package com.yayatechandinnovations.yayaagentic.commerce.loader;

import com.yayatechandinnovations.yayaagentic.commerce.CatalogLocation;
import com.yayatechandinnovations.yayaagentic.commerce.Product;
import com.yayatechandinnovations.yayaagentic.commerce.ProductCatalog;

import java.util.List;

/**
 * Resolves a {@link ProductCatalog}'s location to a concrete product set — the
 * commerce analog of {@code KnowledgeLoader}. Loaders are selected by
 * {@link #supports(CatalogLocation)}, so adding a location variant is additive
 * (the only {@code switch} is the registry). Enumerable locations
 * ({@code Inline}, {@code Database}) load here; the {@code HttpApi} location is
 * searched through the merchant's HTTP tool + {@code ProductProjector} instead.
 * See {@code ecommerce-chat-design.md} §7.1.
 */
public interface CatalogLoader {

    boolean supports(CatalogLocation location);

    /** The full product set for an enumerable catalog. */
    List<Product> load(ProductCatalog catalog);
}
