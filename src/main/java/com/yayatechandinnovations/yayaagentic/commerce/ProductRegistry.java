package com.yayatechandinnovations.yayaagentic.commerce;

import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.util.List;
import java.util.Optional;

/**
 * Read/write authority for managed products (the {@code Database} catalog
 * backing store). Distinct from {@link ProductCatalogRegistry}, which owns
 * versioned catalog <i>config</i>; this owns mutable product <i>data</i>. Powers
 * the admin CRUD table (paged/filtered) and {@code DatabaseCatalogLoader}
 * (enumerate active products). See
 * {@code commerce-redesign-authoring-rendering-paypal.md} Pillar A.
 */
public interface ProductRegistry {

    /** One page of the admin product table (all statuses per the query). */
    ProductPage list(Ids.TenantId tenant, String catalogId, ProductListQuery query);

    /** The full managed product for the editor (any status). */
    Optional<ManagedProduct> find(Ids.TenantId tenant, String catalogId, Ids.ProductId productId);

    /** Active products of a catalog, in author order — the shopper-visible set. */
    List<Product> loadActive(Ids.TenantId tenant, String catalogId);

    long countActive(Ids.TenantId tenant, String catalogId);

    /** Insert or update one product (single-row; no catalog version bump). */
    ManagedProduct save(ManagedProduct product);

    /** Insert/update many in one transaction — backs CSV import and quick-add-many. */
    void saveAll(List<ManagedProduct> products);

    /** Soft archive/restore — a product referenced by history is never hard-deleted. */
    void setStatus(Ids.TenantId tenant, String catalogId, Ids.ProductId productId, String status);
}
