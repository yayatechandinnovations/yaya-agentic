package com.yayatechandinnovations.yayaagentic.commerce.loader;

import com.yayatechandinnovations.yayaagentic.commerce.CatalogLocation;
import com.yayatechandinnovations.yayaagentic.commerce.Product;
import com.yayatechandinnovations.yayaagentic.commerce.ProductCatalog;
import com.yayatechandinnovations.yayaagentic.commerce.ProductRegistry;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Loads a {@code Database} catalog — the managed {@code products} table we own
 * and index. The commerce-redesign counterpart the sealed variant always
 * promised but never shipped (only {@link InlineCatalogLoader} existed). Returns
 * the catalog's <b>active</b> products in author order; archived rows are never
 * surfaced. See {@code commerce-redesign-authoring-rendering-paypal.md} Pillar A.
 */
@Component
public class DatabaseCatalogLoader implements CatalogLoader {

    private final ProductRegistry products;

    public DatabaseCatalogLoader(ProductRegistry products) {
        this.products = products;
    }

    @Override
    public boolean supports(CatalogLocation location) {
        return location instanceof CatalogLocation.Database;
    }

    @Override
    public List<Product> load(ProductCatalog catalog) {
        return products.loadActive(catalog.tenant(), logicalName(catalog));
    }

    /** The row set for this catalog — the Database variant's logicalName, or the catalog's own id. */
    static String logicalName(ProductCatalog catalog) {
        if (catalog.location() instanceof CatalogLocation.Database db
                && db.logicalName() != null && !db.logicalName().isBlank()) {
            return db.logicalName();
        }
        return catalog.id().value();
    }
}
