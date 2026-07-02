package com.yayatechandinnovations.yayaagentic.commerce.loader;

import com.yayatechandinnovations.yayaagentic.commerce.CatalogLocation;
import com.yayatechandinnovations.yayaagentic.commerce.ProductCatalog;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Selects the {@link CatalogLoader} for a catalog's location by asking each
 * {@code supports()} — the one place that fans out over the sealed
 * {@link CatalogLocation} family, so new variants are additive. See
 * {@code ecommerce-chat-design.md} §7.1.
 */
@Component
public class CatalogLoaderRegistry {

    private final List<CatalogLoader> loaders;

    public CatalogLoaderRegistry(List<CatalogLoader> loaders) {
        this.loaders = loaders;
    }

    public Optional<CatalogLoader> loaderFor(CatalogLocation location) {
        return loaders.stream().filter(l -> l.supports(location)).findFirst();
    }

    /** True when this catalog can be enumerated locally (Inline/Database), not searched over HTTP. */
    public boolean isEnumerable(ProductCatalog catalog) {
        return loaderFor(catalog.location()).isPresent();
    }
}
