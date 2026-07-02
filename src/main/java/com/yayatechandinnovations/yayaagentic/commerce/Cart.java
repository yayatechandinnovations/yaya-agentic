package com.yayatechandinnovations.yayaagentic.commerce;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.List;

/**
 * A session's cart snapshot — the shape rendered by the {@code cart} UiHint and
 * threaded across turns in {@code WorkingMemory}. See
 * {@code ecommerce-chat-design.md} §7.3.
 *
 * <p>{@code cartHandle} is the merchant backend's opaque cart id/token when the
 * merchant owns the cart (the default HTTP transport); it is {@code null} in the
 * managed fallback, where this snapshot <i>is</i> the cart of record. Either
 * way, every {@link Money} here is the authoritative price — the merchant's word
 * or the indexed catalog's — never the model's (non-negotiable #1).</p>
 */
public record Cart(
        String cartHandle,
        String currency,
        List<CartLine> lines,
        Money subtotal,
        Instant updatedAt
) {
    public Cart {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public static Cart empty(String currency, Instant at) {
        return new Cart(null, currency, List.of(), Money.zero(currency), at);
    }

    @JsonIgnore
    public boolean isEmpty() {
        return lines.isEmpty();
    }

    @JsonIgnore
    public int itemCount() {
        return lines.stream().mapToInt(CartLine::qty).sum();
    }

    /** One physical line in the cart. {@code lineTotal} is derived, not supplied. */
    public record CartLine(
            String lineId,
            String productId,
            String variantId,
            String title,
            String image,
            int qty,
            Money unitPrice,
            Money lineTotal
    ) {}
}
