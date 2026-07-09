package com.yayatechandinnovations.yayasales.inventory;

import com.yayatechandinnovations.yayasales.platform.Ids.LocationId;
import com.yayatechandinnovations.yayasales.platform.Ids.MovementId;
import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;
import com.yayatechandinnovations.yayasales.platform.money.Money;

import java.time.Instant;

/** §7.1 — one immutable ledger row, as surfaced by the admin ledger endpoint. */
public record MovementView(
        MovementId id,
        VariantId sku,
        LocationId location,
        long qtyDelta,
        MovementKind kind,
        Money unitCost,
        Ref.RefType refType,
        String refId,
        String reason,
        Instant occurredAt,
        Instant recordedAt) {}
