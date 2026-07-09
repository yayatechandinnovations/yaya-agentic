package com.yayatechandinnovations.yayasales.orders;

import java.util.Map;
import java.util.Set;

/** §8.1 — the order FSM. {@link #canTransition} is the single legality check. */
public enum OrderStatus {

    DRAFT,                  // agent/merchant-built, awaiting send (S6)
    PENDING_PAYMENT,
    AUTH_FAILED,
    PAID,
    FULFILLING,             // S5
    PARTIALLY_FULFILLED,    // S5
    FULFILLED,              // S5
    CANCELLED,
    PARTIALLY_REFUNDED,
    REFUNDED;

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.of(
            DRAFT, Set.of(PENDING_PAYMENT, CANCELLED),
            PENDING_PAYMENT, Set.of(PAID, AUTH_FAILED, CANCELLED),
            AUTH_FAILED, Set.of(PENDING_PAYMENT, CANCELLED),
            PAID, Set.of(FULFILLING, PARTIALLY_FULFILLED, FULFILLED, PARTIALLY_REFUNDED, REFUNDED),
            FULFILLING, Set.of(PARTIALLY_FULFILLED, FULFILLED, PARTIALLY_REFUNDED, REFUNDED),
            PARTIALLY_FULFILLED, Set.of(FULFILLED, PARTIALLY_REFUNDED, REFUNDED),
            FULFILLED, Set.of(PARTIALLY_REFUNDED, REFUNDED),
            PARTIALLY_REFUNDED, Set.of(REFUNDED),
            CANCELLED, Set.of(),
            REFUNDED, Set.of());

    public boolean canTransition(OrderStatus to) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(to);
    }
}
