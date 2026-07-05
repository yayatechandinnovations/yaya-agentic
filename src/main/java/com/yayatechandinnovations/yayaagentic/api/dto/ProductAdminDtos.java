package com.yayatechandinnovations.yayaagentic.api.dto;

import java.util.List;
import java.util.Map;

/**
 * Admin REST DTOs for managed products (the {@code Database} catalog backing
 * store). The <b>row</b> view is the lightweight projection the paged table
 * renders; the <b>detail</b> view carries every field the full editor needs.
 * Prices are always integer minor units — the client converts a decimal entry
 * to minor units before sending, so the model/UI never hands us a float (#1).
 * See {@code commerce-redesign-authoring-rendering-paypal.md} Pillar A.
 */
public final class ProductAdminDtos {

    private ProductAdminDtos() {}

    /** Lightweight table row. */
    public record ProductRow(
            String productId,
            String title,
            long priceMinor,
            String currency,
            String thumbnail,        // first image, or null
            String availability,
            Integer inventory,
            String status
    ) {}

    public record ProductsPage(
            List<ProductRow> items,
            int page,
            int size,
            long total
    ) {}

    public record VariantView(
            String variantId,
            String label,
            long priceMinor,
            String currency,
            String sku,
            String availability
    ) {}

    /** Full product for the editor. */
    public record ProductDetail(
            String productId,
            String title,
            String shortDescription,
            String longDescription,
            long priceMinor,
            String currency,
            String category,
            Map<String, String> attributes,
            List<String> imageUrls,
            List<VariantView> variants,
            String availability,
            Integer inventory,
            String detailUrl,
            String status,
            int sortOrder
    ) {}

    /** Create/update one product (POST list, PUT item, and each bulk element). */
    public record SaveProductRequest(
            String productId,
            String title,
            String shortDescription,
            String longDescription,
            Long priceMinor,
            String currency,          // defaults to the catalog currency when blank
            String category,
            Map<String, String> attributes,
            List<String> imageUrls,
            List<VariantView> variants,
            String availability,
            Integer inventory,
            String detailUrl,
            Integer sortOrder
    ) {}

    public record BulkUpsertRequest(List<SaveProductRequest> products) {}

    public record RowError(int index, String productId, String reason) {}

    public record BulkUpsertResponse(int upserted, List<RowError> errors) {}
}
