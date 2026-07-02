package com.yayatechandinnovations.yayaagentic.commerce.cart;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.commerce.Cart;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.memory.WorkingMemory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Threads the cart across turns through {@code WorkingMemory} — one slot holding
 * the {@link Cart} snapshot (which carries the merchant {@code cartHandle} when
 * the merchant owns the cart). This is the only place cart state touches
 * session storage; the engine never reaches storage directly (non-negotiable
 * #4). See {@code ecommerce-chat-design.md} §7.3, §8.
 */
@Component
public class CartStore {

    static final String SLOT = "commerceCart";

    private final WorkingMemory memory;
    private final ObjectMapper json;

    public CartStore(WorkingMemory memory, ObjectMapper json) {
        this.memory = memory;
        this.json = json;
    }

    public Optional<Cart> load(Ids.SessionId session) {
        Object raw = memory.get(session).get(SLOT);
        if (raw == null) return Optional.empty();
        return Optional.of(json.convertValue(raw, Cart.class));
    }

    public void save(Ids.SessionId session, Cart cart) {
        memory.merge(session, Map.of(SLOT, cart));
    }

    public void clear(Ids.SessionId session) {
        memory.remove(session, SLOT);
    }
}
