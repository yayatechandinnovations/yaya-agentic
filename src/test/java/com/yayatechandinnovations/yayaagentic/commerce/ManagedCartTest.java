package com.yayatechandinnovations.yayaagentic.commerce;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.commerce.cart.CartStore;
import com.yayatechandinnovations.yayaagentic.commerce.cart.ManagedCart;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.memory.WorkingMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedCartTest {

    private static final Ids.SessionId SESSION = new Ids.SessionId("s1");
    private static final String USD = "USD";

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private ManagedCart cart;

    // The authoritative source: soap = $12.99, oil = $8.50. The caller can NEVER
    // supply a price — it comes from here, which is the whole point of #1.
    private final ProductLookup catalog = id -> switch (id.value()) {
        case "p_soap" -> Optional.of(product("p_soap", "Castor Oil Soap", 1299));
        case "p_oil" -> Optional.of(product("p_oil", "Castor Oil", 850));
        default -> Optional.empty();
    };

    @BeforeEach
    void setUp() {
        cart = new ManagedCart(new CartStore(new InMemoryWorkingMemory(json), json));
    }

    @Test
    void totalIsAuthoritativePriceTimesQty_notModelSupplied() {
        Cart c = cart.add(SESSION, USD, new Ids.ProductId("p_soap"), null, 2, catalog);

        assertEquals(1, c.lines().size());
        assertEquals(2, c.itemCount());
        assertEquals(new Money(1299, USD), c.lines().get(0).unitPrice());
        assertEquals(new Money(2598, USD), c.lines().get(0).lineTotal());
        assertEquals(new Money(2598, USD), c.subtotal());
    }

    @Test
    void addingSameProductMergesTheLineAndAccumulatesQty() {
        cart.add(SESSION, USD, new Ids.ProductId("p_soap"), null, 1, catalog);
        Cart c = cart.add(SESSION, USD, new Ids.ProductId("p_soap"), null, 2, catalog);

        assertEquals(1, c.lines().size());
        assertEquals(3, c.lines().get(0).qty());
        assertEquals(new Money(3897, USD), c.subtotal());
    }

    @Test
    void twoProductsSumIntoSubtotal() {
        cart.add(SESSION, USD, new Ids.ProductId("p_soap"), null, 2, catalog);   // 2598
        Cart c = cart.add(SESSION, USD, new Ids.ProductId("p_oil"), null, 1, catalog); // 850

        assertEquals(2, c.lines().size());
        assertEquals(new Money(3448, USD), c.subtotal());
    }

    @Test
    void setQuantityRepricesFromUnitPrice_andZeroRemovesTheLine() {
        String lineId = cart.add(SESSION, USD, new Ids.ProductId("p_soap"), null, 1, catalog)
                .lines().get(0).lineId();

        Cart c = cart.setQuantity(SESSION, USD, lineId, 4);
        assertEquals(new Money(5196, USD), c.subtotal());

        Cart emptied = cart.setQuantity(SESSION, USD, lineId, 0);
        assertTrue(emptied.isEmpty());
        assertEquals(new Money(0, USD), emptied.subtotal());
    }

    @Test
    void removeAndClearEmptyTheCart() {
        String lineId = cart.add(SESSION, USD, new Ids.ProductId("p_oil"), null, 3, catalog)
                .lines().get(0).lineId();

        assertTrue(cart.remove(SESSION, USD, lineId).isEmpty());

        cart.add(SESSION, USD, new Ids.ProductId("p_oil"), null, 1, catalog);
        assertTrue(cart.clear(SESSION, USD).isEmpty());
    }

    @Test
    void rejectsUnknownProduct() {
        assertThrows(IllegalArgumentException.class,
                () -> cart.add(SESSION, USD, new Ids.ProductId("nope"), null, 1, catalog));
    }

    @Test
    void cartSurvivesAWorkingMemoryRoundTrip() {
        cart.add(SESSION, USD, new Ids.ProductId("p_soap"), null, 2, catalog);
        // A fresh ManagedCart over the same store must see the persisted cart.
        assertEquals(new Money(2598, USD), cart.view(SESSION, USD).subtotal());
    }

    private static Product product(String id, String title, long minor) {
        return new Product(new Ids.ProductId(id), title, "", new Money(minor, USD),
                List.of("https://img/" + id), "body-care", Map.of(), List.of(),
                Availability.IN_STOCK, null);
    }

    /** JSON-round-tripping fake — mirrors RedisWorkingMemory's encode/decode. */
    private static final class InMemoryWorkingMemory implements WorkingMemory {
        private final Map<String, Map<String, String>> store = new LinkedHashMap<>();
        private final ObjectMapper json;

        InMemoryWorkingMemory(ObjectMapper json) { this.json = json; }

        @Override public void set(Ids.SessionId s, Map<String, Object> values) {
            store.put(s.value(), encode(values));
        }
        @Override public void merge(Ids.SessionId s, Map<String, Object> values) {
            store.computeIfAbsent(s.value(), k -> new LinkedHashMap<>()).putAll(encode(values));
        }
        @Override public Map<String, Object> get(Ids.SessionId s) {
            Map<String, String> raw = store.getOrDefault(s.value(), Map.of());
            Map<String, Object> out = new LinkedHashMap<>();
            raw.forEach((k, v) -> {
                try { out.put(k, json.readValue(v, Object.class)); }
                catch (JsonProcessingException e) { throw new IllegalStateException(e); }
            });
            return out;
        }
        @Override public void clear(Ids.SessionId s) { store.remove(s.value()); }
        @Override public void remove(Ids.SessionId s, String... keys) {
            Map<String, String> bag = store.get(s.value());
            if (bag != null) for (String k : keys) bag.remove(k);
        }

        private Map<String, String> encode(Map<String, Object> values) {
            Map<String, String> out = new LinkedHashMap<>();
            values.forEach((k, v) -> {
                try { out.put(k, json.writeValueAsString(v)); }
                catch (JsonProcessingException e) { throw new IllegalStateException(e); }
            });
            return out;
        }
    }
}
