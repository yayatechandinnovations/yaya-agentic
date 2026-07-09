package com.yayatechandinnovations.yayasales.inventory.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Append-only: no setter mutates a persisted row; a DB trigger enforces it too. */
@Entity
@Table(name = "stock_movements")
public class StockMovementEntity {

    @Id
    private String id;

    @Column(name = "store_id", nullable = false)
    private String storeId;

    @Column(name = "sku_id", nullable = false)
    private String skuId;

    @Column(name = "location_id", nullable = false)
    private String locationId;

    @Column(name = "qty_delta", nullable = false)
    private long qtyDelta;

    @Column(nullable = false)
    private String kind;

    @Column(name = "unit_cost_minor")
    private Long unitCostMinor;

    private String currency;

    @Column(name = "ref_type", nullable = false)
    private String refType;

    @Column(name = "ref_id")
    private String refId;

    private String reason;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    protected StockMovementEntity() {}

    public StockMovementEntity(String id, String storeId, String skuId, String locationId,
                               long qtyDelta, String kind, Long unitCostMinor, String currency,
                               String refType, String refId, String reason, Instant at) {
        this.id = id;
        this.storeId = storeId;
        this.skuId = skuId;
        this.locationId = locationId;
        this.qtyDelta = qtyDelta;
        this.kind = kind;
        this.unitCostMinor = unitCostMinor;
        this.currency = currency;
        this.refType = refType;
        this.refId = refId;
        this.reason = reason;
        this.occurredAt = at;
        this.recordedAt = at;
    }

    public String getId() { return id; }
    public String getStoreId() { return storeId; }
    public String getSkuId() { return skuId; }
    public String getLocationId() { return locationId; }
    public long getQtyDelta() { return qtyDelta; }
    public String getKind() { return kind; }
    public Long getUnitCostMinor() { return unitCostMinor; }
    public String getCurrency() { return currency; }
    public String getRefType() { return refType; }
    public String getRefId() { return refId; }
    public String getReason() { return reason; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getRecordedAt() { return recordedAt; }
}
