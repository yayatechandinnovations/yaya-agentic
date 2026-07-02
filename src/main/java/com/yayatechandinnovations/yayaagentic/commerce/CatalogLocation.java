package com.yayatechandinnovations.yayaagentic.commerce;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.yayatechandinnovations.yayaagentic.tool.HttpToolSpec;

import java.util.List;

/**
 * Where a {@link ProductCatalog}'s products come from — the commerce analog of
 * {@code SourceLocation}. Loaders are selected by matching the sealed variant
 * ({@code CatalogLoader.supports()}), so adding a variant is additive; the only
 * {@code switch} is the loader registry. See {@code ecommerce-chat-design.md} §7.1.
 *
 * <p>There is deliberately no server-filesystem variant, for the same
 * path-traversal reason {@code SourceLocation} omits one.</p>
 *
 * <p>The {@code kind} discriminator lets the whole family round-trip through the
 * {@code location_json} column.</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = CatalogLocation.Inline.class, name = "INLINE"),
        @JsonSubTypes.Type(value = CatalogLocation.HttpApi.class, name = "HTTP_API"),
        @JsonSubTypes.Type(value = CatalogLocation.Database.class, name = "DATABASE")
})
public sealed interface CatalogLocation
        permits CatalogLocation.Inline,
                CatalogLocation.HttpApi,
                CatalogLocation.Database {

    /** Seed / test / small fixed catalogs — pasted product JSON (like {@code SourceLocation.Inline}). */
    record Inline(List<Product> products) implements CatalogLocation {
        public Inline {
            products = products == null ? List.of() : List.copyOf(products);
        }
    }

    /**
     * The common production case: the merchant already has a product API.
     * Path-only URL templates (like {@code HttpToolSpec}) plus a
     * {@link ProductProjection} mapping their JSON → {@link Product}.
     */
    record HttpApi(
            HttpToolSpec listSpec,
            HttpToolSpec getSpec,
            ProductProjection projection
    ) implements CatalogLocation {}

    /**
     * A managed products table we own and index (for merchants with no API),
     * fed by the admin console or a CSV/file import that reuses the knowledge
     * blob/upload path. {@code logicalName} names the row set in the
     * {@code products} table.
     */
    record Database(String logicalName) implements CatalogLocation {}
}
