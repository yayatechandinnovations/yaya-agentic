package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A local record of a checkout outcome. yaya-agentic is not the fulfilment
 * system of record; this tracks the PSP {@code order_ref} + status so the turn
 * is auditable and a later webhook can flip {@code PENDING} → {@code PAID}. See
 * {@code ecommerce-chat-design.md} §11, §14.
 */
@Entity
@Table(name = "commerce_orders")
public class CommerceOrderEntity {

    @Id
    @Column(nullable = false)
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false, length = 32)
    private String provider;

    @Column(name = "order_ref", nullable = false, length = 128)
    private String orderRef;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 8)
    private String currency;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected CommerceOrderEntity() {}

    public CommerceOrderEntity(UUID id, UUID sessionId, String tenantId, String provider,
                               String orderRef, long amountMinor, String currency, String status) {
        this.id = id;
        this.sessionId = sessionId;
        this.tenantId = tenantId;
        this.provider = provider;
        this.orderRef = orderRef;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.status = status;
    }

    public UUID getId() { return id; }
    public UUID getSessionId() { return sessionId; }
    public String getTenantId() { return tenantId; }
    public String getProvider() { return provider; }
    public String getOrderRef() { return orderRef; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public String getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public void setStatus(String v) { this.status = v; }
}
