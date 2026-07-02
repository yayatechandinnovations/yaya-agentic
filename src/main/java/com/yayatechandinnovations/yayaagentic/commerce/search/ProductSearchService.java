package com.yayatechandinnovations.yayaagentic.commerce.search;

import com.yayatechandinnovations.yayaagentic.auth.Authorizer;
import com.yayatechandinnovations.yayaagentic.auth.AuthzContext;
import com.yayatechandinnovations.yayaagentic.auth.AuthzDecision;
import com.yayatechandinnovations.yayaagentic.commerce.Product;
import com.yayatechandinnovations.yayaagentic.commerce.ProductCatalog;
import com.yayatechandinnovations.yayaagentic.commerce.ProductCatalogRegistry;
import com.yayatechandinnovations.yayaagentic.commerce.ProductLookup;
import com.yayatechandinnovations.yayaagentic.commerce.facet.Facet;
import com.yayatechandinnovations.yayaagentic.commerce.facet.FacetComputer;
import com.yayatechandinnovations.yayaagentic.commerce.loader.CatalogLoader;
import com.yayatechandinnovations.yayaagentic.commerce.loader.CatalogLoaderRegistry;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Searches a profile's product catalogs and derives facets — the deterministic
 * spine behind {@code search_products}. Each catalog is access-gated through the
 * {@link Authorizer} chain; an ineligible catalog is silently dropped, never
 * surfaced (non-negotiable #7 analog). Enumerable catalogs (Inline/Database) are
 * filtered in memory here; {@code HttpApi} catalogs are searched through the
 * merchant's HTTP tool + {@code ProductProjector} instead (Phase 2B), so they are
 * skipped by this local path. See {@code ecommerce-chat-design.md} §8, §10.2.
 */
@Component
public class ProductSearchService {

    private final ProductCatalogRegistry catalogs;
    private final CatalogLoaderRegistry loaders;
    private final FacetComputer facetComputer;
    private final Authorizer authorizer;

    public ProductSearchService(ProductCatalogRegistry catalogs,
                                CatalogLoaderRegistry loaders,
                                FacetComputer facetComputer,
                                Authorizer authorizer) {
        this.catalogs = catalogs;
        this.loaders = loaders;
        this.facetComputer = facetComputer;
        this.authorizer = authorizer;
    }

    public ProductSearchResult search(Ids.TenantId tenant,
                                      List<Ids.ProductCatalogId> catalogIds,
                                      Principal principal,
                                      AuthzContext ctx,
                                      ProductQuery query) {
        List<Product> pool = accessibleProducts(tenant, catalogIds, principal, ctx);

        List<Product> matched = pool.stream().filter(p -> matches(p, query)).toList();
        List<Facet> facets = facetComputer.compute(matched);

        int from = Math.min(query.page() * query.pageSize(), matched.size());
        int to = Math.min(from + query.pageSize(), matched.size());
        List<Product> pageItems = matched.subList(from, to);
        boolean hasMore = to < matched.size();

        return new ProductSearchResult(pageItems, facets, query.page(), hasMore,
                matched.size(), query.text());
    }

    /**
     * A {@link ProductLookup} over the accessible catalogs, so the managed cart
     * always reads authoritative prices from the same access-gated set the
     * shopper can browse.
     */
    public ProductLookup lookup(Ids.TenantId tenant,
                                List<Ids.ProductCatalogId> catalogIds,
                                Principal principal,
                                AuthzContext ctx) {
        List<Product> pool = accessibleProducts(tenant, catalogIds, principal, ctx);
        return id -> pool.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    // --- internals ---------------------------------------------------------

    private List<Product> accessibleProducts(Ids.TenantId tenant,
                                             List<Ids.ProductCatalogId> catalogIds,
                                             Principal principal,
                                             AuthzContext ctx) {
        List<Product> pool = new ArrayList<>();
        for (Ids.ProductCatalogId catalogId : catalogIds) {
            Optional<ProductCatalog> found = catalogs.find(tenant, catalogId);
            if (found.isEmpty()) continue;
            ProductCatalog catalog = found.get();
            if (!allowed(principal, catalog, ctx)) continue;               // #7: drop, don't leak
            loaders.loaderFor(catalog.location())
                    .map(loader -> load(loader, catalog))
                    .ifPresent(pool::addAll);
        }
        return pool;
    }

    private boolean allowed(Principal principal, ProductCatalog catalog, AuthzContext ctx) {
        AuthzDecision decision = authorizer.authorize(principal, catalog.access(), Map.of(), ctx);
        return decision instanceof AuthzDecision.Allow;
    }

    private static List<Product> load(CatalogLoader loader, ProductCatalog catalog) {
        List<Product> products = loader.load(catalog);
        return products == null ? List.of() : products;
    }

    static boolean matches(Product p, ProductQuery q) {
        if (q.priceMax() != null && (p.price() == null || p.price().amountMinor() > q.priceMax())) {
            return false;
        }
        if (q.category() != null && !q.category().isBlank()
                && !q.category().equalsIgnoreCase(p.category())) {
            return false;
        }
        for (Map.Entry<String, String> want : q.attributes().entrySet()) {
            String have = p.attributes().get(want.getKey());
            if (have == null || !have.equalsIgnoreCase(want.getValue())) return false;
        }
        if (q.text() != null && !q.text().isBlank() && !textMatches(p, q.text())) {
            return false;
        }
        return true;
    }

    private static boolean textMatches(Product p, String text) {
        String needle = text.toLowerCase(Locale.ROOT);
        if (contains(p.title(), needle) || contains(p.shortDescription(), needle)
                || contains(p.category(), needle)) {
            return true;
        }
        return p.attributes().values().stream().anyMatch(v -> contains(v, needle));
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
    }
}
