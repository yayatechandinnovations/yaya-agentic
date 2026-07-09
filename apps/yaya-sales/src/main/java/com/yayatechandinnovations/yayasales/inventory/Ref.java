package com.yayatechandinnovations.yayasales.inventory;

import java.util.Objects;

/**
 * Provenance for a movement or reservation (§7.1): WHY stock moved and which
 * aggregate drove it. "Why is on-hand 7?" is answerable down to the movement.
 */
public record Ref(RefType type, String id) {

    public Ref {
        Objects.requireNonNull(type, "ref type is required");
    }

    public enum RefType { CART, ORDER, RMA, PURCHASE_ORDER, ADJUSTMENT, TRANSFER, OPENING_BALANCE }

    public static Ref cart(String cartId) { return new Ref(RefType.CART, cartId); }
    public static Ref order(String orderId) { return new Ref(RefType.ORDER, orderId); }
}
