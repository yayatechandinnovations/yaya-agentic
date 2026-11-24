package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * The router's state for one phone on a platform sender (§7.2, V99): the
 * pinned workspace and an open «¿Con cuál negocio?» question.
 */
@Entity
@Table(name = "whatsapp_shared_routes")
@IdClass(WhatsAppSharedRouteEntity.PK.class)
public class WhatsAppSharedRouteEntity {

    @Id @Column(name = "sender_id", length = 64)
    private String senderId;

    @Id @Column(name = "end_user_phone", length = 20)
    private String endUserPhone;

    @Column(name = "active_tenant_id", length = 64)
    private String activeTenantId;

    @Column(name = "active_at")
    private OffsetDateTime activeAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pending_choice", columnDefinition = "jsonb")
    private String pendingChoice;

    protected WhatsAppSharedRouteEntity() {}

    public WhatsAppSharedRouteEntity(String senderId, String endUserPhone) {
        this.senderId = senderId;
        this.endUserPhone = endUserPhone;
    }

    public String getSenderId() { return senderId; }
    public String getEndUserPhone() { return endUserPhone; }
    public String getActiveTenantId() { return activeTenantId; }
    public void setActiveTenantId(String v) { this.activeTenantId = v; }
    public OffsetDateTime getActiveAt() { return activeAt; }
    public void setActiveAt(OffsetDateTime v) { this.activeAt = v; }
    public String getPendingChoice() { return pendingChoice; }
    public void setPendingChoice(String v) { this.pendingChoice = v; }

    public static class PK implements Serializable {
        private String senderId; private String endUserPhone;
        public PK() {}
        public PK(String senderId, String endUserPhone) {
            this.senderId = senderId; this.endUserPhone = endUserPhone;
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(senderId, pk.senderId) && Objects.equals(endUserPhone, pk.endUserPhone);
        }
        @Override public int hashCode() { return Objects.hash(senderId, endUserPhone); }
    }
}
