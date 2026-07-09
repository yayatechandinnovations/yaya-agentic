package com.yayatechandinnovations.yayasales.purchasing.internal;

import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import com.yayatechandinnovations.yayasales.purchasing.PurchaseOrderView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "purchase_orders")
public class PurchaseOrderEntity {

    @Id
    private String id;

    @Column(name = "store_id", nullable = false)
    private String storeId;

    @Column(name = "supplier_id", nullable = false)
    private String supplierId;

    @Column(name = "location_id", nullable = false)
    private String locationId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "lines_json", nullable = false)
    private String linesJson;

    @Column(nullable = false)
    private String status;

    @Column(name = "expected_at")
    private Instant expectedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PurchaseOrderEntity() {}

    public PurchaseOrderEntity(String id, String storeId, String supplierId, String locationId,
                               String linesJson, Instant expectedAt) {
        this.id = id;
        this.storeId = storeId;
        this.supplierId = supplierId;
        this.locationId = locationId;
        this.linesJson = linesJson;
        this.expectedAt = expectedAt;
        this.status = PurchaseOrderView.Status.DRAFT.name();
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public String getStoreId() { return storeId; }
    public String getSupplierId() { return supplierId; }
    public String getLocationId() { return locationId; }
    public String getLinesJson() { return linesJson; }
    public Instant getExpectedAt() { return expectedAt; }
    public Instant getCreatedAt() { return createdAt; }

    public PurchaseOrderView.Status status() {
        return PurchaseOrderView.Status.valueOf(status);
    }

    /** The lifecycle gate: illegal transitions are conflicts, not silent writes. */
    void transition(PurchaseOrderView.Status to) {
        PurchaseOrderView.Status from = status();
        if (!from.canTransition(to)) {
            throw new ApiErrors.ConflictException(
                    "purchase order " + id + " cannot go " + from + " → " + to);
        }
        this.status = to.name();
    }
}
