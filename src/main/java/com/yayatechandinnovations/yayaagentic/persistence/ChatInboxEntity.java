package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * One row of {@code chat_inbox} (V64) — store-and-forward for chat delivery
 * (§11). The row exists the moment ingest returns; the operator drains
 * undelivered rows on next open. {@code body} is the delivery copy; the recorded
 * Turn is the audit source of truth.
 */
@Entity
@Table(name = "chat_inbox")
public class ChatInboxEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "entry_point_id", nullable = false, length = 64)
    private String entryPointId;

    @Column(name = "recipient_key", nullable = false, length = 255)
    private String recipientKey;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "turn_id", length = 64)
    private String turnId;

    @Column(name = "outreach_id", length = 64)
    private String outreachId;

    @Column(name = "sender_display", length = 120)
    private String senderDisplay;

    @Column(name = "subject", length = 200)
    private String subject;

    @Column(name = "body")
    private String body;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "correlation_json", columnDefinition = "jsonb")
    private String correlationJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "media_json", columnDefinition = "jsonb", nullable = false)
    private String mediaJson = "[]";

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "delivered_at")
    private OffsetDateTime deliveredAt;

    protected ChatInboxEntity() {}

    public ChatInboxEntity(String tenantId, String entryPointId, String recipientKey, String sessionId) {
        this.tenantId = tenantId;
        this.entryPointId = entryPointId;
        this.recipientKey = recipientKey;
        this.sessionId = sessionId;
    }

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getEntryPointId() { return entryPointId; }
    public String getRecipientKey() { return recipientKey; }
    public String getSessionId() { return sessionId; }
    public String getTurnId() { return turnId; }
    public void setTurnId(String v) { this.turnId = v; }
    public String getOutreachId() { return outreachId; }
    public void setOutreachId(String v) { this.outreachId = v; }
    public String getSenderDisplay() { return senderDisplay; }
    public void setSenderDisplay(String v) { this.senderDisplay = v; }
    public String getSubject() { return subject; }
    public void setSubject(String v) { this.subject = v; }
    public String getBody() { return body; }
    public void setBody(String v) { this.body = v; }
    public String getCorrelationJson() { return correlationJson; }
    public void setCorrelationJson(String v) { this.correlationJson = v; }
    public String getMediaJson() { return mediaJson; }
    public void setMediaJson(String v) { this.mediaJson = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(OffsetDateTime v) { this.deliveredAt = v; }
}
