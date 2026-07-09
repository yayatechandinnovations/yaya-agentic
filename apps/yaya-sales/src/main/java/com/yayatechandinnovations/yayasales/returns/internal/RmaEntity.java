package com.yayatechandinnovations.yayasales.returns.internal;

import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import com.yayatechandinnovations.yayasales.returns.RmaStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "rmas")
public class RmaEntity {

    @Id
    private String id;

    @Column(name = "order_id", nullable = false)
    private String orderId;

    @Column(name = "store_id", nullable = false)
    private String storeId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "lines_json", nullable = false)
    private String linesJson;

    @Column(nullable = false)
    private String status;

    @Column
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "return_shipment_json")
    private String returnShipmentJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dispositions_json")
    private String dispositionsJson;

    @Column(name = "refund_txn_ref")
    private String refundTxnRef;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RmaEntity() {}

    public RmaEntity(String id, String orderId, String storeId, String linesJson, String reason) {
        this.id = id;
        this.orderId = orderId;
        this.storeId = storeId;
        this.linesJson = linesJson;
        this.reason = reason;
        this.status = RmaStatus.REQUESTED.name();
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public String getOrderId() { return orderId; }
    public String getStoreId() { return storeId; }
    public String getLinesJson() { return linesJson; }
    public String getReason() { return reason; }
    public String getReturnShipmentJson() { return returnShipmentJson; }
    public String getDispositionsJson() { return dispositionsJson; }
    public String getRefundTxnRef() { return refundTxnRef; }
    public Instant getReceivedAt() { return receivedAt; }
    public Instant getCreatedAt() { return createdAt; }

    void setReason(String reason) { this.reason = reason; }
    void setReturnShipmentJson(String returnShipmentJson) { this.returnShipmentJson = returnShipmentJson; }
    void setDispositionsJson(String dispositionsJson) { this.dispositionsJson = dispositionsJson; }
    void setRefundTxnRef(String refundTxnRef) { this.refundTxnRef = refundTxnRef; }
    void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }

    public RmaStatus status() {
        return RmaStatus.valueOf(status);
    }

    /** The lifecycle gate (§8.8): illegal transitions are conflicts, not silent writes. */
    void transition(RmaStatus to) {
        RmaStatus from = status();
        if (!from.canTransition(to)) {
            throw new ApiErrors.ConflictException(
                    "rma " + id + " cannot go " + from + " → " + to);
        }
        this.status = to.name();
    }
}
