package com.yayatechandinnovations.yayasales.inventory;

import com.yayatechandinnovations.yayasales.platform.Ids.ReservationId;

import java.time.Instant;

/** §7.3 — the guarded reserve outcome. {@code Insufficient} carries what IS available. */
public sealed interface ReserveResult permits ReserveResult.Held, ReserveResult.Insufficient {

    record Held(ReservationId id, Instant expiresAt) implements ReserveResult {}

    record Insufficient(long available) implements ReserveResult {}
}
