package com.yayatechandinnovations.yayasales.inventory.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "reservations")
public class ReservationEntity {

    @Id
    private String id;

    @Column(name = "store_id", nullable = false)
    private String storeId;

    @Column(name = "sku_id", nullable = false)
    private String skuId;

    @Column(name = "location_id", nullable = false)
    private String locationId;

    @Column(nullable = false)
    private long qty;

    @Column(nullable = false)
    private String state;      // HELD | COMMITTED | RELEASED | EXPIRED

    @Column(nullable = false)
    private String kind;       // SOFT | FIRM

    @Column(name = "cart_id")
    private String cartId;

    @Column(name = "order_id")
    private String orderId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ReservationEntity() {}

    public ReservationEntity(String id, String storeId, String skuId, String locationId,
                             long qty, String kind, String cartId, String orderId,
                             Instant expiresAt, Instant createdAt) {
        this.id = id;
        this.storeId = storeId;
        this.skuId = skuId;
        this.locationId = locationId;
        this.qty = qty;
        this.state = "HELD";
        this.kind = kind;
        this.cartId = cartId;
        this.orderId = orderId;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getStoreId() { return storeId; }
    public String getSkuId() { return skuId; }
    public String getLocationId() { return locationId; }
    public long getQty() { return qty; }
    public String getState() { return state; }
    public String getKind() { return kind; }
    public String getCartId() { return cartId; }
    public String getOrderId() { return orderId; }
    public Instant getExpiresAt() { return expiresAt; }

    public boolean held() { return "HELD".equals(state); }
    public boolean lapsed(Instant now) { return held() && expiresAt.isBefore(now); }

    public void firmUp(Instant newExpiry) {
        this.kind = "FIRM";
        this.expiresAt = newExpiry;
    }

    public void markCommitted() { this.state = "COMMITTED"; }
    public void markReleased() { this.state = "RELEASED"; }
    public void markExpired() { this.state = "EXPIRED"; }
}
