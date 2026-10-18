package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * A conversation a person took over: until {@code held_until}, inbound from this phone is acked and stored but the
 * engine is not invoked. See {@code docs/design/whatsapp-channel-design.md} §11.3.
 */
@Entity
@Table(name = "conversation_holds")
@IdClass(ConversationHoldEntity.PK.class)
public class ConversationHoldEntity {

    @Id @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Id @Column(name = "end_user_phone", length = 20)
    private String endUserPhone;

    @Column(name = "held_until", nullable = false)
    private OffsetDateTime heldUntil;

    @Column(name = "reason", length = 64)
    private String reason;

    @Column(name = "held_from", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime heldFrom;

    protected ConversationHoldEntity() {}

    public ConversationHoldEntity(String tenantId, String endUserPhone, OffsetDateTime heldUntil, String reason) {
        this.tenantId = tenantId;
        this.endUserPhone = endUserPhone;
        this.heldUntil = heldUntil;
        this.reason = reason;
    }

    public String getTenantId() { return tenantId; }
    public String getEndUserPhone() { return endUserPhone; }
    public OffsetDateTime getHeldUntil() { return heldUntil; }
    public String getReason() { return reason; }
    public OffsetDateTime getHeldFrom() { return heldFrom; }

    public void setHeldUntil(OffsetDateTime v) { this.heldUntil = v; }
    public void setReason(String v) { this.reason = v; }

    public static class PK implements Serializable {
        private String tenantId; private String endUserPhone;
        public PK() {}
        public PK(String tenantId, String endUserPhone) {
            this.tenantId = tenantId; this.endUserPhone = endUserPhone;
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(tenantId, pk.tenantId)
                    && Objects.equals(endUserPhone, pk.endUserPhone);
        }
        @Override public int hashCode() { return Objects.hash(tenantId, endUserPhone); }
    }
}
