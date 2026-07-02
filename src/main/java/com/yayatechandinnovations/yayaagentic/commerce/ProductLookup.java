package com.yayatechandinnovations.yayaagentic.commerce;

import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.util.Optional;

/**
 * Resolves a product (and thus its authoritative {@link Money} price) by id —
 * the source of truth the managed cart consults so a price is <b>never</b> taken
 * from an LLM tool argument (non-negotiable #1). In the managed fallback this is
 * backed by the indexed catalog; a {@code CatalogLoader}-backed implementation
 * arrives in Phase 2. See {@code ecommerce-chat-design.md} §7.3, §8.
 */
public interface ProductLookup {

    /** The product, or empty if it is not in any catalog visible to this session. */
    Optional<Product> find(Ids.ProductId productId);
}
