package com.yayatechandinnovations.yayaagentic.commerce;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.knowledge.RetrievalPolicy;

/**
 * A {@code KnowledgeSource}-shaped primitive for products, so operators,
 * storage, and access-gating all feel familiar. {@code access} gates the whole
 * catalog: ineligible catalogs are silently dropped from search (existence is
 * not leaked — non-negotiable #7 analog). See {@code ecommerce-chat-design.md} §7.1.
 *
 * <p>{@code search} reuses the knowledge {@link RetrievalPolicy} so a
 * natural-language product query ("gift for a dry scalp") can rank via the
 * existing pgvector pipeline — no new paid dependency.</p>
 */
public record ProductCatalog(
        Ids.ProductCatalogId id,
        Ids.TenantId tenant,
        String displayName,
        CatalogLocation location,
        CatalogSyncPolicy sync,
        RetrievalPolicy search,
        PermissionRequirement access,
        String currency,
        /** Optional representative image URL for the catalog (nullable). */
        String imageUrl
) {
    /** Back-compat: a catalog without a representative image. */
    public ProductCatalog(Ids.ProductCatalogId id, Ids.TenantId tenant, String displayName,
                          CatalogLocation location, CatalogSyncPolicy sync, RetrievalPolicy search,
                          PermissionRequirement access, String currency) {
        this(id, tenant, displayName, location, sync, search, access, currency, null);
    }
}
