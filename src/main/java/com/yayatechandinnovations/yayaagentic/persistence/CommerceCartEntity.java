package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Durable projection of a session cart. Working memory is the hot path; this is
 * the durable fallback (reconnect + webhook-clear-after-TTL) and the
 * abandoned-cart recovery source. See {@code ecommerce-chat-design.md} §7.3, §14.
 */
@Entity
@Table(name = "commerce_carts")
public class CommerceCartEntity {

    @Id
    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "cart_json", columnDefinition = "jsonb", nullable = false)
    private String cartJson;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected CommerceCartEntity() {}

    public CommerceCartEntity(UUID sessionId, String tenantId, String cartJson) {
        this.sessionId = sessionId;
        this.tenantId = tenantId;
        this.cartJson = cartJson;
    }

    public UUID getSessionId() { return sessionId; }
    public String getTenantId() { return tenantId; }
    public String getCartJson() { return cartJson; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setCartJson(String v) { this.cartJson = v; }
    public void setTenantId(String v) { this.tenantId = v; }
}
