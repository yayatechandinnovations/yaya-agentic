package com.yayatechandinnovations.yayaagentic.commerce.search;

import com.yayatechandinnovations.yayaagentic.auth.Authorizer;
import com.yayatechandinnovations.yayaagentic.auth.AuthzContext;
import com.yayatechandinnovations.yayaagentic.auth.AuthzDecision;
import com.yayatechandinnovations.yayaagentic.commerce.Availability;
import com.yayatechandinnovations.yayaagentic.commerce.CatalogLocation;
import com.yayatechandinnovations.yayaagentic.commerce.CatalogSyncPolicy;
import com.yayatechandinnovations.yayaagentic.commerce.Money;
import com.yayatechandinnovations.yayaagentic.commerce.Product;
import com.yayatechandinnovations.yayaagentic.commerce.ProductCatalog;
import com.yayatechandinnovations.yayaagentic.commerce.ProductCatalogRegistry;
import com.yayatechandinnovations.yayaagentic.commerce.facet.FacetComputer;
import com.yayatechandinnovations.yayaagentic.commerce.loader.CatalogLoaderRegistry;
import com.yayatechandinnovations.yayaagentic.commerce.loader.InlineCatalogLoader;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductSearchServiceTest {

    private static final Ids.TenantId TENANT = new Ids.TenantId("t1");
    private static final Ids.ProductCatalogId PUBLIC_CAT = new Ids.ProductCatalogId("public", 1);
    private static final Ids.ProductCatalogId VIP_CAT = new Ids.ProductCatalogId("vip", 1);

    private final AuthzContext ctx = new AuthzContext(
            new Ids.SessionId("s1"), new Ids.TurnId("u1"), "trace", Map.of());

    // Deny a catalog whose access requires a scope the principal lacks; else allow.
    private final Authorizer scopeGate = (principal, requirement, args, c) -> {
        boolean ok = principal.scopes().containsAll(requirement.requiredScopes());
        return ok ? AuthzDecision.Allow.none()
                : new AuthzDecision.Deny("Not available", "missing scope " + requirement.requiredScopes());
    };

    private ProductSearchService service(ProductCatalogRegistry registry) {
        CatalogLoaderRegistry loaders = new CatalogLoaderRegistry(List.of(new InlineCatalogLoader()));
        return new ProductSearchService(registry, loaders, new FacetComputer(), scopeGate);
    }

    @Test
    void filtersByCategoryAndComputesFacets() {
        ProductSearchService service = service(registryWith(publicCatalog()));
        ProductQuery query = new ProductQuery(null, "soap", Map.of(), null, 0, 20);

        ProductSearchResult result = service.search(
                TENANT, List.of(PUBLIC_CAT), guest(), ctx, query);

        assertTrue(result.items().stream().allMatch(p -> p.category().equals("soap")));
        assertEquals(2, result.total());
        assertTrue(result.facets().stream().anyMatch(f -> f.key().equals("ingredient")));
    }

    @Test
    void priceMaxFiltersOutExpensiveItems() {
        ProductSearchService service = service(registryWith(publicCatalog()));
        ProductQuery query = new ProductQuery(null, null, Map.of(), 1000L, 0, 20);

        ProductSearchResult result = service.search(
                TENANT, List.of(PUBLIC_CAT), guest(), ctx, query);

        assertTrue(result.items().stream().allMatch(p -> p.price().amountMinor() <= 1000));
    }

    @Test
    void textMatchesTitleDescriptionAndAttributes() {
        ProductSearchService service = service(registryWith(publicCatalog()));

        ProductSearchResult result = service.search(
                TENANT, List.of(PUBLIC_CAT), guest(), ctx, ProductQuery.of("castor"));

        assertFalse(result.items().isEmpty());
        assertTrue(result.items().stream()
                .allMatch(p -> p.title().toLowerCase().contains("castor")
                        || p.attributes().getOrDefault("ingredient", "").contains("castor")));
    }

    @Test
    void ineligibleCatalogIsSilentlyDropped() {
        ProductSearchService service = service(registryWith(publicCatalog(), vipCatalog()));

        // Guest lacks the "vip" scope → VIP catalog products never appear.
        ProductSearchResult asGuest = service.search(
                TENANT, List.of(PUBLIC_CAT, VIP_CAT), guest(), ctx, ProductQuery.of(""));
        assertTrue(asGuest.items().stream().noneMatch(p -> p.id().value().startsWith("vip_")));

        // A VIP principal sees the gated products.
        ProductSearchResult asVip = service.search(
                TENANT, List.of(PUBLIC_CAT, VIP_CAT), vip(), ctx, ProductQuery.of(""));
        assertTrue(asVip.items().stream().anyMatch(p -> p.id().value().startsWith("vip_")));
    }

    @Test
    void paginates() {
        ProductSearchService service = service(registryWith(publicCatalog()));
        ProductQuery page0 = new ProductQuery(null, null, Map.of(), null, 0, 2);

        ProductSearchResult first = service.search(TENANT, List.of(PUBLIC_CAT), guest(), ctx, page0);
        assertEquals(2, first.items().size());
        assertTrue(first.hasMore());

        ProductQuery page1 = new ProductQuery(null, null, Map.of(), null, 1, 2);
        ProductSearchResult second = service.search(TENANT, List.of(PUBLIC_CAT), guest(), ctx, page1);
        assertFalse(second.hasMore());
    }

    @Test
    void lookupRespectsAccessGating() {
        ProductSearchService service = service(registryWith(publicCatalog(), vipCatalog()));

        assertTrue(service.lookup(TENANT, List.of(VIP_CAT), guest(), ctx)
                .find(new Ids.ProductId("vip_1")).isEmpty());
        assertTrue(service.lookup(TENANT, List.of(VIP_CAT), vip(), ctx)
                .find(new Ids.ProductId("vip_1")).isPresent());
    }

    // --- fixtures ----------------------------------------------------------

    private static Principal guest() {
        return new Principal("guest", TENANT, Set.of(), Map.of(), Instant.EPOCH);
    }

    private static Principal vip() {
        return new Principal("vipuser", TENANT, Set.of("vip"), Map.of(), Instant.EPOCH);
    }

    private ProductCatalogRegistry registryWith(ProductCatalog... cats) {
        Map<String, ProductCatalog> byId = new java.util.LinkedHashMap<>();
        for (ProductCatalog c : cats) byId.put(c.id().value(), c);
        return new ProductCatalogRegistry() {
            @Override public Optional<ProductCatalog> find(Ids.TenantId t, Ids.ProductCatalogId id) {
                return Optional.ofNullable(byId.get(id.value()));
            }
            @Override public List<ProductCatalog> listFor(Ids.TenantId t) { return List.copyOf(byId.values()); }
            @Override public void register(ProductCatalog catalog) { byId.put(catalog.id().value(), catalog); }
        };
    }

    private static ProductCatalog publicCatalog() {
        List<Product> products = List.of(
                product("p1", "Castor Oil Soap", 899, "soap", Map.of("ingredient", "castor oil")),
                product("p2", "Shea Soap", 1299, "soap", Map.of("ingredient", "shea")),
                product("p3", "Castor Oil", 1599, "oil", Map.of("ingredient", "castor oil")));
        return catalog(PUBLIC_CAT, PermissionRequirement.none(), products);
    }

    private static ProductCatalog vipCatalog() {
        List<Product> products = List.of(
                product("vip_1", "Limited Balm", 4999, "balm", Map.of("ingredient", "shea")));
        PermissionRequirement gated = new PermissionRequirement(Set.of("vip"), List.of(), null);
        return catalog(VIP_CAT, gated, products);
    }

    private static ProductCatalog catalog(Ids.ProductCatalogId id, PermissionRequirement access,
                                          List<Product> products) {
        return new ProductCatalog(id, TENANT, id.value(),
                new CatalogLocation.Inline(products), CatalogSyncPolicy.live(),
                com.yayatechandinnovations.yayaagentic.knowledge.RetrievalPolicy.defaults(),
                access, "USD");
    }

    private static Product product(String id, String title, long minor, String category,
                                   Map<String, String> attrs) {
        return new Product(new Ids.ProductId(id), title, "", new Money(minor, "USD"),
                List.of(), category, attrs, List.of(), Availability.IN_STOCK, null);
    }
}
