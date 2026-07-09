package com.yayatechandinnovations.yayasales.payments.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "order_tenders")
public class OrderTenderEntity {

    @Id
    private String id;

    @Column(name = "order_id", nullable = false)
    private String orderId;

    @Column(name = "store_id", nullable = false)
    private String storeId;

    @Column(nullable = false)
    private String kind;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false)
    private String currency;

    @Column(nullable = false)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail_json")
    private String detailJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    protected OrderTenderEntity() {}

    public OrderTenderEntity(String id, String orderId, String storeId, String kind,
                             long amountMinor, String currency) {
        this.id = id;
        this.orderId = orderId;
        this.storeId = storeId;
        this.kind = kind;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.status = "PENDING";
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public String getOrderId() { return orderId; }
    public String getStoreId() { return storeId; }
    public String getKind() { return kind; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public String getStatus() { return status; }
    public String getDetailJson() { return detailJson; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSettledAt() { return settledAt; }

    public boolean pending() { return "PENDING".equals(status); }
    public boolean settled() { return "SETTLED".equals(status); }

    public void setDetailJson(String detailJson) { this.detailJson = detailJson; }

    public void markSettled(Instant at) {
        this.status = "SETTLED";
        this.settledAt = at;
    }

    public void markVoided() { this.status = "VOIDED"; }

    public void markRefunded() { this.status = "REFUNDED"; }
}
