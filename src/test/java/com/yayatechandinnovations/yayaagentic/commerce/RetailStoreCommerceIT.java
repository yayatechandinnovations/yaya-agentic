package com.yayatechandinnovations.yayaagentic.commerce;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.commerce.tool.CommerceTools;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.engine.ConversationEngine;
import com.yayatechandinnovations.yayaagentic.engine.TurnEvent;
import com.yayatechandinnovations.yayaagentic.engine.bootstrap.RetailStoreBootstrap;
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
 * End-to-end proof that the seeded {@code retail-store} profile transacts through
 * the action channel: a tap on {@code add_to_cart} runs the managed cart tool,
 * prices from the seeded catalog, and streams back a {@code cart} UiHint — with no
 * merchant wiring and no LLM. Validates seeding → WM commerce config → tool →
 * PendingUiHints drain → SSE events, all at once.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
class RetailStoreCommerceIT {

    @Autowired
    ConversationEngine engine;

    private AuthContext auth() {
        return new AuthContext(RetailStoreBootstrap.DEFAULT_TENANT, Map.of(), Optional.empty());
    }

    private Ids.SessionId storeSession() {
        return engine.start(new StartConversationRequest(
                        RetailStoreBootstrap.DEFAULT_TENANT,
                        Optional.of(RetailStoreBootstrap.RETAIL_STORE_PROFILE),
                        "web", Map.of()),
                auth()).session().id();
    }

    private List<TurnEvent> action(Ids.SessionId sid, String tool, Map<String, Object> args) {
        return engine.dispatchAction(sid, new Ids.ToolId(tool), args, auth()).collectList().block();
    }

    @Test
    void tappingAddToCartPricesFromCatalogAndEmitsCartHint() {
        Ids.SessionId sid = storeSession();

        List<TurnEvent> events = action(sid, CommerceTools.ADD_TO_CART,
                Map.of("productId", "p_castor_soap", "qty", 2));

        // Compact tool result: 2 × 899 = 1798, priced by the catalog (not the caller).
        TurnEvent.ToolResult result = firstToolResult(events);
        assertEquals("OK", result.status().name());
        @SuppressWarnings("unchecked")
        Map<String, Object> value = (Map<String, Object>) result.value();
        assertEquals(2, ((Number) value.get("itemCount")).intValue());
        assertEquals(1798L, ((Number) value.get("subtotalMinor")).longValue());

        // The render payload is a cart UiHint, not prose.
        TurnEvent.UiHint cart = firstHint(events, "cart");
        assertNotNull(cart, "expected a cart UiHint");
        assertTrue(cart.payload().containsKey("lines"));
    }

    @Test
    void tappingCheckoutRaisesTheConfirmGateAndDoesNotChargeYet() {
        Ids.SessionId sid = storeSession();
        action(sid, CommerceTools.ADD_TO_CART, Map.of("productId", "p_castor_soap", "qty", 1));

        List<TurnEvent> events = action(sid, CommerceTools.CHECKOUT, Map.of());

        // Confirmable: a tap starts the two-phase confirm, it does not skip it.
        assertNotNull(firstHint(events, "confirm"), "checkout must raise a confirm UiHint");
        // No payment happened yet — no checkout_result on this turn.
        assertEquals(null, firstHint(events, "checkout_result"));
    }

    @Test
    void tappingSearchProductsEmitsCatalogWithFacets() {
        Ids.SessionId sid = storeSession();

        List<TurnEvent> events = action(sid, CommerceTools.SEARCH_PRODUCTS, Map.of("category", "soap"));

        TurnEvent.UiHint catalog = firstHint(events, "product_catalog");
        assertNotNull(catalog, "expected a product_catalog UiHint");
        assertFalse(((List<?>) catalog.payload().get("items")).isEmpty());
        assertFalse(((List<?>) catalog.payload().get("facets")).isEmpty());
    }

    // --- helpers -----------------------------------------------------------

    private static TurnEvent.ToolResult firstToolResult(List<TurnEvent> events) {
        return events.stream()
                .filter(e -> e instanceof TurnEvent.ToolResult)
                .map(e -> (TurnEvent.ToolResult) e)
                .findFirst().orElseThrow();
    }

    private static TurnEvent.UiHint firstHint(List<TurnEvent> events, String kind) {
        return events.stream()
                .filter(e -> e instanceof TurnEvent.UiHint u && u.kind().equals(kind))
                .map(e -> (TurnEvent.UiHint) e)
                .findFirst().orElse(null);
    }
}
