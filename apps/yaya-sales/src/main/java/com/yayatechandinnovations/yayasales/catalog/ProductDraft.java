package com.yayatechandinnovations.yayasales.catalog;

import com.yayatechandinnovations.yayasales.platform.money.Money;

import java.util.List;
import java.util.Map;

/** Authoring input for create/update. Null collection = leave as-is on update, empty on create. */
public record ProductDraft(
        String title,
        String description,
        String category,
        Map<String, String> attributes,
        List<String> media,
        Map<String, Object> metadata,
        ProductStatus status,
        Integer sortOrder,
        List<VariantDraft> variants) {

    public record VariantDraft(
            String sku,
            String label,
            Money price,
            Map<String, String> options,
            Integer weightGrams,
            String barcode) {}
}
