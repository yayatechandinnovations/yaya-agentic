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
import com.yayatechandinnovations.yayaagentic.commerce.payment.DLocalPaymentProvider;
import com.yayatechandinnovations.yayaagentic.commerce.payment.DLocalProperties;
import com.yayatechandinnovations.yayaagentic.commerce.payment.PaymentProviderRouter;
import com.yayatechandinnovations.yayaagentic.commerce.payment.PaymentRoutingProperties;
import com.yayatechandinnovations.yayaagentic.commerce.payment.PayPalPaymentProvider;
import com.yayatechandinnovations.yayaagentic.commerce.payment.PayPalProperties;
import com.yayatechandinnovations.yayaagentic.commerce.payment.StripeConnectPaymentProvider;
import com.yayatechandinnovations.yayaagentic.commerce.payment.StripeProperties;
import com.yayatechandinnovations.yayaagentic.commerce.payment.onboarding.MerchantPspAccountService;
import com.yayatechandinnovations.yayaagentic.commerce.search.ProductSearchService;
import com.yayatechandinnovations.yayaagentic.commerce.testsupport.JsonWorkingMemory;
import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.engine.PendingUiHints;
import com.yayatechandinnovations.yayaagentic.knowledge.RetrievalPolicy;
import com.yayatechandinnovations.yayaagentic.memory.WorkingMemory;
import com.yayatechandinnovations.yayaagentic.persistence.CommerceOrderEntity;
import com.yayatechandinnovations.yayaagentic.persistence.CommerceOrderRepository;
import com.yayatechandinnovations.yayaagentic.tool.ToolException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CheckoutToolHandlerTest {

    private static final Ids.TenantId TENANT = new Ids.TenantId("t1");
    private static final Ids.SessionId SESSION =
            new Ids.SessionId("11112222-3333-4444-5555-666677778888");
    private static final Ids.ProductCatalogId CATALOG = new Ids.ProductCatalogId("cat", 1);

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private WorkingMemory wm;
    private PendingUiHints pending;
    private ManagedCart cart;
    private CommerceRuntime runtime;
    private CheckoutToolHandler checkout;
    private PaymentProviderRouter router;
    private CommerceOrderRepository orders;

    @BeforeEach
    void setUp() {
        wm = new JsonWorkingMemory(json);
        pending = new PendingUiHints(wm);
        CommercePayloads payloads = new CommercePayloads(json);
        cart = new ManagedCart(new CartStore(wm, json));
        orders = mock(CommerceOrderRepository.class);
        when(orders.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ProductSearchService search = new ProductSearchService(
                registry(), new CatalogLoaderRegistry(List.of(new InlineCatalogLoader())),
                new FacetComputer(), (p, r, a, c) -> AuthzDecision.Allow.none());
        runtime = new CommerceRuntime(wm, json, search, pending);

        router = new PaymentProviderRouter(
                new DLocalPaymentProvider(new DLocalProperties(null, null, null, null, null), json),
                new StripeConnectPaymentProvider(new StripeProperties(null, null, null, null), json),
                new PayPalPaymentProvider(new PayPalProperties(null, null, null, null, null, null), json),
                new PaymentRoutingProperties("auto"));   // default = dLocal

        // No in-app onboarding record in these unit tests → checkout falls back to
        // the profile's connectedAccountId (or platform charge).
        MerchantPspAccountService merchantAccounts = mock(MerchantPspAccountService.class);
        when(merchantAccounts.resolveConnectedAccount(any(), any())).thenReturn(Optional.empty());
        checkout = new CheckoutToolHandler(runtime, cart, router, merchantAccounts, orders, payloads);

        wm.merge(SESSION, Map.of("commerceConfig",
                new CommerceConfig(List.of(CATALOG), "USD", new Ids.ToolId("checkout"), true, null)));
    }

    @Test
    void checkoutOpensPaymentOnTheAuthoritativeTotalAndEmitsCheckoutResult() {
        CommerceConfig cfg = new CommerceConfig(List.of(CATALOG), "USD", null, true, null);
        cart.add(SESSION, "USD", new Ids.ProductId("p1"), null, 2, runtime.lookup(ctx(), cfg));
        pending.drain(SESSION);

        Map<String, Object> out = checkout.execute(Map.of(), ctx());

        assertEquals(1798L, ((Number) out.get("amountMinor")).longValue()); // 2 × 899, from the catalog
        assertEquals("USD", out.get("currency"));
        assertEquals("PENDING", out.get("status"));                          // hosted-url flow not yet paid
        assertTrue(String.valueOf(out.get("orderRef")).startsWith("ord_"));

        assertEquals(CommercePayloads.CHECKOUT_RESULT, pending.drain(SESSION).get(0).kind());
        verify(orders).save(any(CommerceOrderEntity.class));

        // Hosted-url flow keeps the cart until payment is confirmed.
        assertEquals(1, cart.view(SESSION, "USD").lines().size());
    }

    @Test
    void perProfilePaymentProviderOverrideSelectsThatPsp() {
        CommerceConfig paypalCfg = new CommerceConfig(
                List.of(CATALOG), "USD", new Ids.ToolId("checkout"), true, null, "paypal");
        wm.merge(SESSION, Map.of("commerceConfig", paypalCfg));

        cart.add(SESSION, "USD", new Ids.ProductId("p1"), null, 1, runtime.lookup(ctx(), paypalCfg));
        pending.drain(SESSION);

        Map<String, Object> out = checkout.execute(Map.of(), ctx());

        // PayPal's sandbox order ref is "ppl_…" (dLocal's is "ord_…"), and the audit
        // row records "paypal" — the profile's override routed the checkout, not the
        // framework default (which would be dLocal here).
        assertTrue(String.valueOf(out.get("orderRef")).startsWith("ppl_"));
        ArgumentCaptor<CommerceOrderEntity> saved = ArgumentCaptor.forClass(CommerceOrderEntity.class);
        verify(orders).save(saved.capture());
        assertEquals("paypal", saved.getValue().getProvider());
    }

    @Test
    void checkoutRejectsAnEmptyCart() {
        assertThrows(ToolException.class, () -> checkout.execute(Map.of(), ctx()));
    }

    private ExecutionContext ctx() {
        return new ExecutionContext(
                new Principal("shopper", TENANT, Set.of(), Map.of(), Instant.EPOCH),
                SESSION, new Ids.TurnId("u1"), "trace", Map.of());
    }

    private ProductCatalogRegistry registry() {
        ProductCatalog catalog = new ProductCatalog(CATALOG, TENANT, "Body Care",
                new CatalogLocation.Inline(List.of(new Product(new Ids.ProductId("p1"),
                        "Castor Oil Soap", "", new Money(899, "USD"), List.of(), "soap",
                        Map.of(), List.of(), Availability.IN_STOCK, null))),
                CatalogSyncPolicy.live(), RetrievalPolicy.defaults(), PermissionRequirement.none(), "USD");
        return new ProductCatalogRegistry() {
            @Override public Optional<ProductCatalog> find(Ids.TenantId t, Ids.ProductCatalogId id) {
                return id.equals(CATALOG) ? Optional.of(catalog) : Optional.empty();
            }
            @Override public List<ProductCatalog> listFor(Ids.TenantId t) { return List.of(catalog); }
            @Override public void register(ProductCatalog c) { }
        };
    }
}
