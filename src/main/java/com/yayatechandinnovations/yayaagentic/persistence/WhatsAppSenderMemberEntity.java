package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

/** A phone an assigned workspace registered as its staff on a platform sender (§7.6, V99). */
@Entity
@Table(name = "whatsapp_sender_members")
@IdClass(WhatsAppSenderMemberEntity.PK.class)
public class WhatsAppSenderMemberEntity {

    @Id @Column(name = "sender_id", length = 64)
    private String senderId;

    @Id @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Id @Column(name = "phone_e164", length = 20)
    private String phoneE164;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected WhatsAppSenderMemberEntity() {}

    public WhatsAppSenderMemberEntity(String senderId, String tenantId, String phoneE164) {
        this.senderId = senderId;
        this.tenantId = tenantId;
        this.phoneE164 = phoneE164;
        this.updatedAt = OffsetDateTime.now();
    }

    public String getSenderId() { return senderId; }
    public String getTenantId() { return tenantId; }
    public String getPhoneE164() { return phoneE164; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public static class PK implements Serializable {
        private String senderId; private String tenantId; private String phoneE164;
        public PK() {}
        public PK(String senderId, String tenantId, String phoneE164) {
            this.senderId = senderId; this.tenantId = tenantId; this.phoneE164 = phoneE164;
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(senderId, pk.senderId) && Objects.equals(tenantId, pk.tenantId)
                    && Objects.equals(phoneE164, pk.phoneE164);
        }
        @Override public int hashCode() { return Objects.hash(senderId, tenantId, phoneE164); }
    }
}
