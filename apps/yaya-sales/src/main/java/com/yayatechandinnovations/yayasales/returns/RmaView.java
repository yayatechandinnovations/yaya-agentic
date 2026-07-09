package com.yayatechandinnovations.yayasales.returns;

import com.yayatechandinnovations.yayasales.platform.Ids.OrderId;
import com.yayatechandinnovations.yayasales.platform.Ids.RmaId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;

import java.time.Instant;
import java.util.List;

/**
 * §8.8 — one return, end to end: what was authorized to come back, how it
 * travels back (v1: metadata on the RMA, not a fulfillment shipment), what
 * condition it arrived in, and how the money went out.
 */
public record RmaView(
        RmaId id,
        OrderId order,
        StoreId store,
        RmaStatus status,
        String reason,
        List<Line> lines,
        ReturnShipment returnShipment,
        List<Disposition> dispositions,
        String refundRef,
        Instant receivedAt,
        Instant createdAt) {

    public record Line(VariantId sku, long qty) {}

    /** Reverse-logistics metadata (§8.8): a label ref or drop-off/pickup instructions. */
    public record ReturnShipment(String carrier, String trackingNumber, String instructions) {}

    /**
     * What the goods turned out to be at receipt. Only RESTOCK posts a ledger
     * movement — SHRINKAGE/DAMAGE means the units never re-enter stock, so the
     * disposition is audit trail, not a stock move (§8.8).
     */
    public record Disposition(VariantId sku, long qty, Kind kind) {

        public enum Kind { RESTOCK, SHRINKAGE, DAMAGE }
    }
}
