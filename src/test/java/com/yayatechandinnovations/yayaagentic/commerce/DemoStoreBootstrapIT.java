package com.yayatechandinnovations.yayaagentic.commerce;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.commerce.tool.CommerceTools;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.engine.ConversationEngine;
import com.yayatechandinnovations.yayaagentic.engine.TurnEvent;
import com.yayatechandinnovations.yayaagentic.engine.bootstrap.DemoStoreBootstrap;
import com.yayatechandinnovations.yayaagentic.persistence.ChatEntryPointEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChatEntryPointRepository;
import com.yayatechandinnovations.yayaagentic.profile.Profile;
import com.yayatechandinnovations.yayaagentic.profile.ProfileRegistry;
import com.yayatechandinnovations.yayaagentic.profile.StartConversationRequest;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the always-on demo storefront is seeded end-to-end at startup and that
 * a shopper reaches it through the managed {@code Database} catalog: the seeded
 * {@code demo-store} catalog + products, the commerce-bearing {@code demo-store}
 * profile, and the ACTIVE chat entry point at handle {@code demo-store}. Also
 * proves re-running the bootstrap is idempotent (no duplicate/clobbered rows).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
class DemoStoreBootstrapIT {

    @Autowired ConversationEngine engine;
    @Autowired ProductRegistry products;
    @Autowired ProductCatalogRegistry catalogs;
    @Autowired ProfileRegistry profiles;
    @Autowired ChatEntryPointRepository entryPoints;
    @Autowired DemoStoreBootstrap bootstrap;

    @Test
    void seedsAManagedCatalogWithProducts() {
        Optional<ProductCatalog> catalog = catalogs.find(
                DemoStoreBootstrap.DEMO_TENANT, DemoStoreBootstrap.DEMO_STORE_CATALOG);
        assertTrue(catalog.isPresent(), "demo-store catalog must be seeded");
        assertTrue(catalog.get().location() instanceof CatalogLocation.Database,
                "demo store must use the managed Database catalog (the redesign path)");

        long active = products.countActive(DemoStoreBootstrap.DEMO_TENANT, "demo-store");
        assertTrue(active >= 10, "at least 10 sample products, was " + active);
    }

    @Test
    void seedsACommerceProfile() {
        Optional<Profile> profile = profiles.find(
                DemoStoreBootstrap.DEMO_TENANT, DemoStoreBootstrap.DEMO_STORE_PROFILE);
        assertTrue(profile.isPresent(), "demo-store profile must be seeded");
        assertTrue(profile.get().isStore(), "demo-store profile must be a store");
        assertEquals(List.of(DemoStoreBootstrap.DEMO_STORE_CATALOG),
                profile.get().commerce().catalogs());
    }

    @Test
    void seedsAnActiveChatEntryPointBoundToTheProfile() {
        Optional<ChatEntryPointEntity> ep = entryPoints.findByHandle(DemoStoreBootstrap.DEMO_STORE_HANDLE);
        assertTrue(ep.isPresent(), "demo-store chat entry point must be seeded");
        assertEquals("ACTIVE", ep.get().getStatus());
        assertEquals(DemoStoreBootstrap.DEMO_STORE_PROFILE.value(), ep.get().getProfileId());
    }

    @Test
    void shopperSearchReachesTheDatabaseCatalog() {
        Ids.SessionId sid = engine.start(new StartConversationRequest(
                        DemoStoreBootstrap.DEMO_TENANT,
                        Optional.of(DemoStoreBootstrap.DEMO_STORE_PROFILE),
                        "web", Map.of()),
                auth()).session().id();

        List<TurnEvent> events = engine.dispatchAction(
                sid, new Ids.ToolId(CommerceTools.SEARCH_PRODUCTS), Map.of("category", "beans"), auth())
                .collectList().block();

        TurnEvent.UiHint catalog = firstHint(events, "product_catalog");
        assertNotNull(catalog, "expected a product_catalog UiHint from the managed catalog");
        assertFalse(((List<?>) catalog.payload().get("items")).isEmpty(),
                "the coffee-bean facet must return seeded products");
    }

    @Test
    void reRunningTheBootstrapIsIdempotent() {
        long before = products.countActive(DemoStoreBootstrap.DEMO_TENANT, "demo-store");
        bootstrap.run(null);
        long after = products.countActive(DemoStoreBootstrap.DEMO_TENANT, "demo-store");
        assertEquals(before, after, "re-seeding must not duplicate products");
        assertEquals(1, entryPoints.findByTenantIdOrderByCreatedAtDesc(
                DemoStoreBootstrap.DEMO_TENANT.value()).stream()
                .filter(e -> DemoStoreBootstrap.DEMO_STORE_HANDLE.equals(e.getHandle()))
                .count(), "re-seeding must not duplicate the entry point");
    }

    private static AuthContext auth() {
        return new AuthContext(DemoStoreBootstrap.DEMO_TENANT, Map.of(), Optional.empty());
    }

    private static TurnEvent.UiHint firstHint(List<TurnEvent> events, String kind) {
        return events.stream()
                .filter(e -> e instanceof TurnEvent.UiHint u && u.kind().equals(kind))
                .map(e -> (TurnEvent.UiHint) e)
                .findFirst().orElse(null);
    }
}
