package com.yayatechandinnovations.yayaagentic.commerce.loader;

import com.yayatechandinnovations.yayaagentic.commerce.CatalogLocation;
import com.yayatechandinnovations.yayaagentic.commerce.Product;
import com.yayatechandinnovations.yayaagentic.commerce.ProductCatalog;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Loads an {@code Inline} catalog — pasted product JSON for seed / test / small
 * fixed catalogs. This is the zero-dependency path that proves browse → filter
 * without any merchant wiring. See {@code ecommerce-chat-design.md} §7.1.
 */
@Component
public class InlineCatalogLoader implements CatalogLoader {

    @Override
    public boolean supports(CatalogLocation location) {
        return location instanceof CatalogLocation.Inline;
    }

    @Override
    public List<Product> load(ProductCatalog catalog) {
        return ((CatalogLocation.Inline) catalog.location()).products();
    }
}
