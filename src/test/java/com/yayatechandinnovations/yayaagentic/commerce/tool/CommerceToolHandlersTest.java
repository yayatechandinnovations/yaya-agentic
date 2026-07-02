package com.yayatechandinnovations.yayaagentic.commerce.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.auth.AuthzDecision;
import com.yayatechandinnovations.yayaagentic.commerce.Availability;
import com.yayatechandinnovations.yayaagentic.commerce.CatalogLocation;
import com.yayatechandinnovations.yayaagentic.commerce.CatalogSyncPolicy;
import com.yayatechandinnovations.yayaagentic.commerce.CommerceConfig;
import com.yayatechandinnovations.yayaagentic.commerce.CommercePayloads;
import com.yayatechandinnovations.yayaagentic.commerce.Money;
import com.yayatechandinnovations.yayaagentic.commerce.Product;
import com.yayatechandinnovations.yayaagentic.commerce.ProductCatalog;
import com.yayatechandinnovations.yayaagentic.commerce.ProductCatalogRegistry;
import com.yayatechandinnovations.yayaagentic.commerce.cart.CartStore;
import com.yayatechandinnovations.yayaagentic.commerce.cart.ManagedCart;
import com.yayatechandinnovations.yayaagentic.commerce.facet.FacetComputer;
import com.yayatechandinnovations.yayaagentic.commerce.loader.CatalogLoaderRegistry;
import com.yayatechandinnovations.yayaagentic.commerce.loader.InlineCatalogLoader;
import com.yayatechandinnovations.yayaagentic.commerce.search.ProductSearchService;
import com.yayatechandinnovations.yayaagentic.commerce.testsupport.JsonWorkingMemory;
import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.engine.PendingUiHints;
import com.yayatechandinnovations.yayaagentic.engine.TurnEvent;
import com.yayatechandinnovations.yayaagentic.knowledge.RetrievalPolicy;
import com.yayatechandinnovations.yayaagentic.memory.WorkingMemory;
import com.yayatechandinnovations.yayaagentic.tool.ToolException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommerceToolHandlersTest {

    private static final Ids.TenantId TENANT = new Ids.TenantId("t1");
    private static final Ids.SessionId SESSION = new Ids.SessionId("s1");
    private static final Ids.ProductCatalogId CATALOG = new Ids.ProductCatalogId("cat", 1);

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private WorkingMemory wm;
    private PendingUiHints pending;
    private CommerceRuntime runtime;
    private ManagedCart cart;
    private CommercePayloads payloads;

    @BeforeEach
    void setUp() {
        wm = new JsonWorkingMemory(json);
        pending = new PendingUiHints(wm);
        payloads = new CommercePayloads(json);
        cart = new ManagedCart(new CartStore(wm, json));

        ProductSearchService search = new ProductSearchService(
                registry(), new CatalogLoaderRegistry(List.of(new InlineCatalogLoader())),
                new FacetComputer(),
                (principal, requirement, args, ctx) -> AuthzDecision.Allow.none());
        runtime = new CommerceRuntime(wm, json, search, pending);

        // Engine stashes this at session start; simulate it here.
        wm.merge(SESSION, Map.of("commerceConfig",
                new CommerceConfig(List.of(CATALOG), "USD", null, true, null)));
    }

    @Test
    void searchPaintsProductCatalogAndReturnsCompactCount() {
        var handler = new SearchProductsToolHandler(runtime, payloads);

        Map<String, Object> out = handler.execute(Map.of("category", "soap"), ctx());

        assertEquals(2, out.get("count"));               // two soaps in the fixture
        List<TurnEvent.UiHint> hints = pending.drain(SESSION);
        assertEquals(1, hints.size());
        assertEquals(CommercePayloads.PRODUCT_CATALOG, hints.get(0).kind());
        assertFalse(((List<?>) hints.get(0).payload().get("items")).isEmpty());
        assertFalse(((List<?>) hints.get(0).payload().get("facets")).isEmpty());
    }

    @Test
    void addToCartPricesFromCatalog_ignoringAnyModelSuppliedPrice() {
        var handler = new AddToCartToolHandler(runtime, cart, payloads);

        // The model tries to smuggle a price; the handler has no price field and
        // re-derives from the catalog (soap = 899 minor).
        Map<String, Object> out = handler.execute(
                Map.of("productId", "p1", "qty", 2, "price", 9999), ctx());

        assertEquals(2, out.get("itemCount"));
        assertEquals(1798L, out.get("subtotalMinor"));   // 2 × 899, NOT 9999
        assertEquals("USD", out.get("currency"));

        List<TurnEvent.UiHint> hints = pending.drain(SESSION);
        assertEquals(CommercePayloads.CART, hints.get(0).kind());
    }

    @Test
    void cartLifecycleAcrossTools() {
        new AddToCartToolHandler(runtime, cart, payloads)
                .execute(Map.of("productId", "p1", "qty", 1), ctx());
        pending.drain(SESSION);

        Map<String, Object> viewed = new ViewCartToolHandler(runtime, cart, payloads)
                .execute(Map.of(), ctx());
        assertEquals(1, viewed.get("itemCount"));

        Map<String, Object> cleared = new ClearCartToolHandler(runtime, cart, payloads)
                .execute(Map.of(), ctx());
        assertEquals(0, cleared.get("itemCount"));
        assertEquals(0L, cleared.get("subtotalMinor"));
    }

    @Test
    void getProductPaintsCardOrReportsNotFound() {
        var handler = new GetProductToolHandler(runtime, payloads);

        Map<String, Object> hit = handler.execute(Map.of("productId", "p1"), ctx());
        assertEquals(true, hit.get("found"));
        assertEquals(CommercePayloads.PRODUCT_CARD, pending.drain(SESSION).get(0).kind());

        Map<String, Object> miss = handler.execute(Map.of("productId", "nope"), ctx());
        assertEquals(false, miss.get("found"));
        assertTrue(pending.drain(SESSION).isEmpty());    // no card emitted for a miss
    }

    @Test
    void toolWithoutCommerceConfigFailsClosed() {
        wm.remove(SESSION, "commerceConfig");
        var handler = new SearchProductsToolHandler(runtime, payloads);
        assertThrows(ToolException.class, () -> handler.execute(Map.of(), ctx()));
    }

    // --- fixtures ----------------------------------------------------------

    private ExecutionContext ctx() {
        Principal principal = new Principal("shopper", TENANT, Set.of(), Map.of(), Instant.EPOCH);
        return new ExecutionContext(principal, SESSION, new Ids.TurnId("u1"), "trace", Map.of());
    }

    private ProductCatalogRegistry registry() {
        ProductCatalog catalog = new ProductCatalog(CATALOG, TENANT, "Body Care",
                new CatalogLocation.Inline(List.of(
                        product("p1", "Castor Oil Soap", 899, "soap", Map.of("ingredient", "castor oil")),
                        product("p2", "Shea Soap", 1299, "soap", Map.of("ingredient", "shea")),
                        product("p3", "Castor Oil", 1599, "oil", Map.of("ingredient", "castor oil")))),
                CatalogSyncPolicy.live(), RetrievalPolicy.defaults(),
                PermissionRequirement.none(), "USD");
        return new ProductCatalogRegistry() {
            @Override public Optional<ProductCatalog> find(Ids.TenantId t, Ids.ProductCatalogId id) {
                return id.equals(CATALOG) ? Optional.of(catalog) : Optional.empty();
            }
            @Override public List<ProductCatalog> listFor(Ids.TenantId t) { return List.of(catalog); }
            @Override public void register(ProductCatalog c) { }
        };
    }

    private static Product product(String id, String title, long minor, String category,
                                   Map<String, String> attrs) {
        return new Product(new Ids.ProductId(id), title, "", new Money(minor, "USD"),
                List.of(), category, attrs, List.of(), Availability.IN_STOCK, null);
    }
}
