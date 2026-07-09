package com.yayatechandinnovations.yayasales.catalog;

import com.yayatechandinnovations.yayasales.platform.Ids.CatalogId;
import com.yayatechandinnovations.yayasales.platform.Ids.ProductId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;

import java.util.List;
import java.util.Optional;

/**
 * Catalog + product authoring (module design §3–§5). Archiving is reversible
 * and never deletes history; publishing bumps the catalog version.
 */
public interface CatalogService {

    CatalogView createCatalog(StoreId store, String displayName);

    CatalogView publish(CatalogId id);

    List<CatalogView> catalogsOf(StoreId store);

    Optional<CatalogView> catalog(CatalogId id);

    ProductView createProduct(CatalogId catalog, ProductDraft draft);

    ProductView updateProduct(ProductId id, ProductDraft draft);

    ProductView archive(ProductId id);

    ProductView restore(ProductId id);

    Optional<ProductView> product(ProductId id);

    /** Resolve the product owning a variant — how cart/orders turn a SKU id into merchandise. */
    Optional<ProductView> productOfVariant(com.yayatechandinnovations.yayasales.platform.Ids.VariantId variant);

    List<ProductView> productsOf(CatalogId catalog);

    List<ProductView> productsOfStore(StoreId store);
}
