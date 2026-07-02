package com.yayatechandinnovations.yayaagentic.commerce;

import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.util.List;
import java.util.Optional;

/**
 * Read/write access to {@link ProductCatalog}s — the commerce analog of
 * {@code ProfileRegistry}/{@code KnowledgeSource} storage. See
 * {@code ecommerce-chat-design.md} §7.1, §14.
 */
public interface ProductCatalogRegistry {

    Optional<ProductCatalog> find(Ids.TenantId tenant, Ids.ProductCatalogId id);

    List<ProductCatalog> listFor(Ids.TenantId tenant);

    void register(ProductCatalog catalog);
}
