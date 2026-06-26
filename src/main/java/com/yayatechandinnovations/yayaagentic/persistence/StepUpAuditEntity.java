package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * One row of {@code step_up_audit} — the dedicated compliance trail for
 * step-up cycles. Each verification cycle produces a {@code started} row
 * and one terminating row ({@code verified} / {@code failed:&lt;reason&gt;} /
 * {@code expired} / {@code aborted}).
 *
 * <p>Tokens, codes, deep-link bytes, and unmasked target addresses NEVER
 * land here. See {@code whatsapp-channel-auth-design.md} §6.7.
 */
@Entity
@Table(name = "step_up_audit")
public class StepUpAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "sender_id", length = 64)
    private String senderId;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(nullable = false, length = 255)
    private String audience;

    @Column(nullable = false, length = 64)
    private String method;

    @Column(name = "initial_subject", nullable = false, length = 255)
    private String initialSubject;

    @Column(name = "elevated_subject", length = 255)
    private String elevatedSubject;

    @Column(nullable = false, length = 64)
    private String outcome;

    @Column(name = "started_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "target_address", length = 255)
    private String targetAddress;

    @Column(name = "correlation_ulid", nullable = false, length = 26)
    private String correlationUlid;

    @Column(name = "pending_id")
    private Long pendingId;

    protected StepUpAuditEntity() {}

    public StepUpAuditEntity(String tenantId, String senderId, String sessionId,
                             String audience, String method,
                             String initialSubject, String elevatedSubject,
                             String outcome, OffsetDateTime completedAt,
                             String targetAddress, String correlationUlid,
                             Long pendingId) {
        this.tenantId = tenantId;
        this.senderId = senderId;
        this.sessionId = sessionId;
        this.audience = audience;
        this.method = method;
        this.initialSubject = initialSubject;
        this.elevatedSubject = elevatedSubject;
        this.outcome = outcome;
        this.completedAt = completedAt;
        this.targetAddress = targetAddress;
        this.correlationUlid = correlationUlid;
        this.pendingId = pendingId;
    }

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getSenderId() { return senderId; }
    public String getSessionId() { return sessionId; }
    public String getAudience() { return audience; }
    public String getMethod() { return method; }
    public String getInitialSubject() { return initialSubject; }
    public String getElevatedSubject() { return elevatedSubject; }
    public String getOutcome() { return outcome; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public String getTargetAddress() { return targetAddress; }
    public String getCorrelationUlid() { return correlationUlid; }
    public Long getPendingId() { return pendingId; }
}
