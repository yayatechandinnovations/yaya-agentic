package com.yayatechandinnovations.yayasales.returns;

import java.util.Map;
import java.util.Set;

/** §8.8 — the RMA lifecycle. {@link #canTransition} is the single legality check. */
public enum RmaStatus {

    REQUESTED,
    AUTHORIZED,
    RECEIVED,
    CLOSED,
    REJECTED,
    CANCELLED;

    private static final Map<RmaStatus, Set<RmaStatus>> ALLOWED = Map.of(
            REQUESTED, Set.of(AUTHORIZED, REJECTED, CANCELLED),
            AUTHORIZED, Set.of(RECEIVED, CANCELLED),
            RECEIVED, Set.of(CLOSED),
            CLOSED, Set.of(),
            REJECTED, Set.of(),
            CANCELLED, Set.of());

    public boolean canTransition(RmaStatus to) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(to);
    }
}
