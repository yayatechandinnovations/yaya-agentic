package com.yayatechandinnovations.yayasales.purchasing.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "goods_receipts")
public class GoodsReceiptEntity {

    @Id
    private String id;

    @Column(name = "purchase_order_id", nullable = false)
    private String purchaseOrderId;

    @Column(name = "store_id", nullable = false)
    private String storeId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "lines_json", nullable = false)
    private String linesJson;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected GoodsReceiptEntity() {}

    public GoodsReceiptEntity(String id, String purchaseOrderId, String storeId,
                              String linesJson) {
        this.id = id;
        this.purchaseOrderId = purchaseOrderId;
        this.storeId = storeId;
        this.linesJson = linesJson;
        this.receivedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getPurchaseOrderId() { return purchaseOrderId; }
    public String getStoreId() { return storeId; }
    public String getLinesJson() { return linesJson; }
    public Instant getReceivedAt() { return receivedAt; }
}
