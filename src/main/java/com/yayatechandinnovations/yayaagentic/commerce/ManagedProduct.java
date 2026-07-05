package com.yayatechandinnovations.yayaagentic.commerce;

import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.time.OffsetDateTime;

/**
 * A managed product row — the render/cart contract ({@link Product}) plus the
 * admin-only, operational metadata that never reaches the shopper ({@code
 * longDescription}, {@code inventory}, {@code status}, {@code sortOrder}). The
 * {@code catalogId} is the <i>logical</i> catalog id (version-independent): a
 * product belongs to a catalog, not to a catalog <i>version</i>, so editing a
 * product never bumps the catalog config version. See
 * {@code commerce-redesign-authoring-rendering-paypal.md} Pillar A.
 */
public record ManagedProduct(
        Ids.TenantId tenant,
        String catalogId,
        Product product,
        String longDescription,
        Integer inventory,
        String status,
        int sortOrder,
        OffsetDateTime updatedAt
) {
    public static final String ACTIVE = "ACTIVE";
    public static final String ARCHIVED = "ARCHIVED";
}
