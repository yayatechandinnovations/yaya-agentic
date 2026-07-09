package com.yayatechandinnovations.yayasales.payments.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Append-only credit ledger row; balance is the sum, never a column. */
@Entity
@Table(name = "store_credit_ledger")
public class StoreCreditEntity {

    @Id
    private String id;

    @Column(name = "store_id", nullable = false)
    private String storeId;

    @Column(name = "customer_ref", nullable = false)
    private String customerRef;

    @Column(name = "delta_minor", nullable = false)
    private long deltaMinor;

    @Column(nullable = false)
    private String currency;

    @Column(nullable = false)
    private String reason;

    @Column(name = "ref_id")
    private String refId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected StoreCreditEntity() {}

    public StoreCreditEntity(String id, String storeId, String customerRef, long deltaMinor,
                             String currency, String reason, String refId) {
        this.id = id;
        this.storeId = storeId;
        this.customerRef = customerRef;
        this.deltaMinor = deltaMinor;
        this.currency = currency;
        this.reason = reason;
        this.refId = refId;
        this.createdAt = Instant.now();
    }

    public long getDeltaMinor() { return deltaMinor; }
    public String getCurrency() { return currency; }
}
