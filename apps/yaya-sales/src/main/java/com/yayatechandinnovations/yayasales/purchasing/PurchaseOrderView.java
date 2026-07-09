package com.yayatechandinnovations.yayasales.purchasing;

import com.yayatechandinnovations.yayasales.platform.Ids.GoodsReceiptId;
import com.yayatechandinnovations.yayasales.platform.Ids.LocationId;
import com.yayatechandinnovations.yayasales.platform.Ids.PurchaseOrderId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.SupplierId;
import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * §9 — a purchase order with its cumulative receipt history: each line knows
 * how much of it has arrived, and the status follows (received vs ordered).
 */
public record PurchaseOrderView(
        PurchaseOrderId id,
        StoreId store,
        SupplierId supplier,
        LocationId location,
        Status status,
        List<Line> lines,
        List<Receipt> receipts,
        Instant expectedAt,
        Instant createdAt) {

    /** {@link #canTransition} gates place/cancel; receive drives the *_RECEIVED pair. */
    public enum Status {
        DRAFT, PLACED, PARTIALLY_RECEIVED, RECEIVED, CANCELLED;

        private static final Map<Status, Set<Status>> ALLOWED = Map.of(
                DRAFT, Set.of(PLACED, CANCELLED),
                PLACED, Set.of(PARTIALLY_RECEIVED, RECEIVED, CANCELLED),
                PARTIALLY_RECEIVED, Set.of(RECEIVED),
                RECEIVED, Set.of(),
                CANCELLED, Set.of());

        public boolean canTransition(Status to) {
            return ALLOWED.getOrDefault(this, Set.of()).contains(to);
        }
    }

    public record Line(VariantId sku, long qty, long unitCostMinor, long receivedQty) {}

    public record Receipt(GoodsReceiptId id, List<ReceiptLine> lines, Instant receivedAt) {}

    public record ReceiptLine(VariantId sku, long qty, long unitCostMinor) {}
}
