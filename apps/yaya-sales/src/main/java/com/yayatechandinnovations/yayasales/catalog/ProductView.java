package com.yayatechandinnovations.yayasales.catalog;

import com.yayatechandinnovations.yayasales.platform.Ids.CatalogId;
import com.yayatechandinnovations.yayasales.platform.Ids.ProductId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;
import com.yayatechandinnovations.yayasales.platform.money.Money;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ProductView(
        ProductId id,
        StoreId store,
        CatalogId catalog,
        String title,
        String description,
        String category,
        Map<String, String> attributes,
        List<String> media,
        Map<String, Object> metadata,
        ProductStatus status,
        int sortOrder,
        List<VariantView> variants,
        Instant createdAt,
        Instant updatedAt) {

    public record VariantView(
            VariantId id,
            String sku,
            String label,
            Money price,
            Map<String, String> options,
            Integer weightGrams,
            String barcode) {}
}
