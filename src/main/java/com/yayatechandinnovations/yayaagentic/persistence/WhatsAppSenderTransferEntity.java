package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * One move of a WhatsApp sender between workspaces — the audit trail and the
 * webhook re-point retry queue (whatsapp-sender-transfer-design.md §6.4, §7).
 */
@Entity
@Table(name = "whatsapp_sender_transfers")
public class WhatsAppSenderTransferEntity {

    public enum RepointStatus { PENDING, DONE, FAILED }

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "phone_e164", nullable = false, length = 20)
    private String phoneE164;

    @Column(name = "twilio_sender_sid", nullable = false, length = 64)
    private String twilioSenderSid;

    @Column(name = "source_tenant_id", length = 64)
    private String sourceTenantId;

    @Column(name = "source_sender_id", nullable = false, length = 64)
    private String sourceSenderId;

    @Column(name = "dest_tenant_id", length = 64)
    private String destTenantId;

    @Column(name = "dest_sender_id", nullable = false, length = 64)
    private String destSenderId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "effects_json", columnDefinition = "jsonb", nullable = false)
    private String effectsJson = "{}";

    @Column(name = "repoint_status", nullable = false, length = 16)
    private String repointStatus = RepointStatus.PENDING.name();

    @Column(name = "repoint_attempts", nullable = false)
    private int repointAttempts;

    @Column(name = "repoint_last_error", columnDefinition = "TEXT")
    private String repointLastError;

    @Column(name = "requested_by", nullable = false, length = 128)
    private String requestedBy;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "repointed_at")
    private OffsetDateTime repointedAt;

    protected WhatsAppSenderTransferEntity() {}

    public WhatsAppSenderTransferEntity(String id, String phoneE164, String twilioSenderSid,
                                        String sourceTenantId, String sourceSenderId,
                                        String destTenantId, String destSenderId,
                                        String effectsJson, String requestedBy) {
        this.id = id;
        this.phoneE164 = phoneE164;
        this.twilioSenderSid = twilioSenderSid;
        this.sourceTenantId = sourceTenantId;
        this.sourceSenderId = sourceSenderId;
        this.destTenantId = destTenantId;
        this.destSenderId = destSenderId;
        this.effectsJson = effectsJson;
        this.requestedBy = requestedBy;
    }

    public String getId() { return id; }
    public String getPhoneE164() { return phoneE164; }
    public String getTwilioSenderSid() { return twilioSenderSid; }
    public String getSourceTenantId() { return sourceTenantId; }
    public String getSourceSenderId() { return sourceSenderId; }
    public String getDestTenantId() { return destTenantId; }
    public String getDestSenderId() { return destSenderId; }
    public String getEffectsJson() { return effectsJson; }
    public String getRepointStatus() { return repointStatus; }
    public int getRepointAttempts() { return repointAttempts; }
    public String getRepointLastError() { return repointLastError; }
    public String getRequestedBy() { return requestedBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getRepointedAt() { return repointedAt; }

    public void setRepointStatus(String v) { this.repointStatus = v; }
    public void setRepointAttempts(int v) { this.repointAttempts = v; }
    public void setRepointLastError(String v) { this.repointLastError = v; }
    public void setRepointedAt(OffsetDateTime v) { this.repointedAt = v; }
}
