package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * One row of {@code system_inbound_messages} (V64) — the ingest log + dedup
 * ledger. {@code (tenant_id, sender_id, dedup_key)} is unique so at-least-once
 * integrations are safe (§16 #10).
 */
@Entity
@Table(name = "system_inbound_messages")
public class SystemInboundMessageEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "sender_id", nullable = false, length = 64)
    private String senderId;

    @Column(name = "dedup_key", nullable = false, length = 200)
    private String dedupKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "recipient_json", columnDefinition = "jsonb", nullable = false)
    private String recipientJson = "{}";

    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "body")
    private String body;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "correlation_json", columnDefinition = "jsonb")
    private String correlationJson;

    @Column(name = "status", nullable = false, length = 16)
    private String status = "DELIVERED";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json", columnDefinition = "jsonb", nullable = false)
    private String metadataJson = "{}";

    @Column(name = "received_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime receivedAt;

    protected SystemInboundMessageEntity() {}

    public SystemInboundMessageEntity(String id, String tenantId, String senderId, String dedupKey) {
        this.id = id;
        this.tenantId = tenantId;
        this.senderId = senderId;
        this.dedupKey = dedupKey;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getSenderId() { return senderId; }
    public String getDedupKey() { return dedupKey; }
    public String getRecipientJson() { return recipientJson; }
    public void setRecipientJson(String v) { this.recipientJson = v; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String v) { this.sessionId = v; }
    public String getBody() { return body; }
    public void setBody(String v) { this.body = v; }
    public String getCorrelationJson() { return correlationJson; }
    public void setCorrelationJson(String v) { this.correlationJson = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String v) { this.metadataJson = v; }
    public OffsetDateTime getReceivedAt() { return receivedAt; }
}
