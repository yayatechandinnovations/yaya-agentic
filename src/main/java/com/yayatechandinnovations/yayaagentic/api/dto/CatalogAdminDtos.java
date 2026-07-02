package com.yayatechandinnovations.yayaagentic.api.dto;

import java.util.List;
import java.util.Map;

/**
 * Admin REST DTOs for product catalogs. Mirrors the knowledge-source shape:
 * a {@code locationKind} discriminator ({@code INLINE} / {@code HTTP_API} /
 * {@code DATABASE}) plus a raw {@code location} map the operator fills in, and
 * {@code sync}/{@code search}/{@code access} policy maps. See
 * {@code ecommerce-chat-design.md} §7.1, §15.
 */
public final class CatalogAdminDtos {

    private CatalogAdminDtos() {}

    public record CreateProductCatalogRequest(
            String tenant,
            String id,
            String displayName,
            String currency,
            String imageUrl,
            String locationKind,
            Map<String, Object> location,
            Map<String, Object> sync,
            Map<String, Object> search,
            Map<String, Object> access
    ) {}

    public record UpdateProductCatalogRequest(
            String displayName,
            String currency,
            String imageUrl,
            String locationKind,
            Map<String, Object> location,
            Map<String, Object> sync,
            Map<String, Object> search,
            Map<String, Object> access
    ) {}

    public record ProductCatalogView(
            String id,
            int version,
            String tenant,
            String displayName,
            String currency,
            String imageUrl,
            String locationKind,
            int productCount
    ) {}

    /** Full detail for edit-in-place: carries the raw {@code location} map
     *  (products for INLINE, specs + projection for HTTP_API) so the admin form
     *  can rehydrate every field. Returned by {@code GET /{id}} only. */
    public record ProductCatalogDetail(
            String id,
            int version,
            String tenant,
            String displayName,
            String currency,
            String imageUrl,
            String locationKind,
            Map<String, Object> location
    ) {}

    public record ListResponse(List<ProductCatalogView> items) {}
}
