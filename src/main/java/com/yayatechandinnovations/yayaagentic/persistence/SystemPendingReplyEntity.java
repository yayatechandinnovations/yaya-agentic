package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * One row of {@code system_pending_replies} (V65) — the durable correlation for
 * the SM-2 reply loop (§8). Re-captured into entity memory on resume so the
 * response tool binds the originator, never the LLM.
 */
@Entity
@Table(name = "system_pending_replies")
public class SystemPendingReplyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "sender_id", nullable = false, length = 64)
    private String senderId;

    @Column(name = "originator_name", length = 200)
    private String originatorName;

    @Column(name = "originator_ref", nullable = false, length = 255)
    private String originatorRef;

    @Column(name = "subject", length = 200)
    private String subject;

    @Column(name = "status", nullable = false, length = 16)
    private String status = "PENDING";

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    protected SystemPendingReplyEntity() {}

    public SystemPendingReplyEntity(String tenantId, String sessionId, String senderId,
                                    String originatorName, String originatorRef, String subject) {
        this.tenantId = tenantId;
        this.sessionId = sessionId;
        this.senderId = senderId;
        this.originatorName = originatorName;
        this.originatorRef = originatorRef;
        this.subject = subject;
    }

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getSessionId() { return sessionId; }
    public String getSenderId() { return senderId; }
    public String getOriginatorName() { return originatorName; }
    public String getOriginatorRef() { return originatorRef; }
    public String getSubject() { return subject; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(OffsetDateTime v) { this.resolvedAt = v; }
}
