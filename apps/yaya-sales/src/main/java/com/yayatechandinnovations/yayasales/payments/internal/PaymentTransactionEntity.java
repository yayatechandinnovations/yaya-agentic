package com.yayatechandinnovations.yayasales.payments.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "payment_transactions")
public class PaymentTransactionEntity {

    @Id
    private String id;

    @Column(name = "order_id", nullable = false)
    private String orderId;

    @Column(name = "tender_id", nullable = false)
    private String tenderId;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private String kind;         // AUTH | CAPTURE | REFUND | VOID

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false)
    private String currency;

    @Column(name = "psp_ref")
    private String pspRef;

    @Column(nullable = false)
    private String status;       // OK | FAILED

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PaymentTransactionEntity() {}

    public PaymentTransactionEntity(String id, String orderId, String tenderId, String provider,
                                    String kind, long amountMinor, String currency,
                                    String pspRef, String status) {
        this.id = id;
        this.orderId = orderId;
        this.tenderId = tenderId;
        this.provider = provider;
        this.kind = kind;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.pspRef = pspRef;
        this.status = status;
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public String getOrderId() { return orderId; }
    public String getTenderId() { return tenderId; }
    public String getProvider() { return provider; }
    public String getKind() { return kind; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public String getPspRef() { return pspRef; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
