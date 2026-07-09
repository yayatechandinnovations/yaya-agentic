package com.yayatechandinnovations.yayasales.inventory.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * The projection + concurrency guard (§7.2). Maintained in the SAME transaction
 * as every movement/reservation; the ledger wins if they ever disagree.
 */
@Entity
@Table(name = "stock_summary")
public class StockSummaryEntity {

    @Embeddable
    public record Key(@Column(name = "sku_id") String skuId,
                      @Column(name = "location_id") String locationId) implements Serializable {
        public Key {
            Objects.requireNonNull(skuId);
            Objects.requireNonNull(locationId);
        }
    }

    @EmbeddedId
    private Key id;

    @Column(name = "store_id", nullable = false)
    private String storeId;

    @Column(name = "on_hand", nullable = false)
    private long onHand;

    @Column(nullable = false)
    private long reserved;

    @Column(name = "avg_cost_minor")
    private Long avgCostMinor;

    private String currency;

    @Column(name = "reorder_point", nullable = false)
    private int reorderPoint;

    @Column(name = "reorder_qty", nullable = false)
    private int reorderQty;

    @Column(name = "backorder_policy", nullable = false)
    private String backorderPolicy;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StockSummaryEntity() {}

    public StockSummaryEntity(Key id, String storeId, String backorderPolicy) {
        this.id = id;
        this.storeId = storeId;
        this.onHand = 0;
        this.reserved = 0;
        this.reorderPoint = 0;
        this.reorderQty = 0;
        this.backorderPolicy = backorderPolicy;
        this.updatedAt = Instant.now();
    }

    public Key getId() { return id; }
    public String getStoreId() { return storeId; }
    public long getOnHand() { return onHand; }
    public long getReserved() { return reserved; }
    public Long getAvgCostMinor() { return avgCostMinor; }
    public String getCurrency() { return currency; }
    public int getReorderPoint() { return reorderPoint; }
    public int getReorderQty() { return reorderQty; }
    public String getBackorderPolicy() { return backorderPolicy; }

    public long available() { return onHand - reserved; }

    public void applyDelta(long qtyDelta) {
        this.onHand += qtyDelta;
        touch();
    }

    public void addReserved(long qty) {
        this.reserved += qty;
        touch();
    }

    public void drainReserved(long qty) {
        if (reserved - qty < 0) {
            throw new IllegalStateException("reserved would go negative — reservation bookkeeping bug");
        }
        this.reserved -= qty;
        touch();
    }

    /**
     * Moving-average update on a costed receipt (§7.5):
     * {@code avg' = (onHand·avg + qty·cost) / (onHand + qty)}. Call BEFORE
     * {@link #applyDelta} so onHand is the pre-receipt quantity. Integer math
     * only — no float on the money path (§5.4).
     */
    public void absorbCost(long qty, long unitCostMinor, String currency) {
        if (this.avgCostMinor == null || this.onHand <= 0) {
            this.avgCostMinor = unitCostMinor;
        } else {
            this.avgCostMinor = java.math.BigDecimal.valueOf(this.onHand)
                    .multiply(java.math.BigDecimal.valueOf(this.avgCostMinor))
                    .add(java.math.BigDecimal.valueOf(qty)
                            .multiply(java.math.BigDecimal.valueOf(unitCostMinor)))
                    .divide(java.math.BigDecimal.valueOf(this.onHand + qty),
                            0, java.math.RoundingMode.HALF_EVEN)
                    .longValueExact();
        }
        this.currency = currency;
        touch();
    }

    public void setReorder(int point, int qty) {
        this.reorderPoint = point;
        this.reorderQty = qty;
        touch();
    }

    public void setBackorderPolicy(String policy) {
        this.backorderPolicy = policy;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
