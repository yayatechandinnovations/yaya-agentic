package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Per-attempt operator login audit. Every login attempt — allow or deny,
 * any source, any reason — produces one row. Password is never recorded.
 */
@Entity
@Table(name = "audit_operator_login")
public class AuditOperatorLoginEntity {

    @Id
    @Column(nullable = false)
    private String id;

    @Column(name = "at", nullable = false)
    private OffsetDateTime at;

    @Column(nullable = false)
    private String username;

    @Column(nullable = false)
    private String decision;          // ALLOW | DENY

    @Column
    private String source;            // BOOTSTRAP | HTTP_DELEGATE | null

    @Column(name = "audit_reason")
    private String auditReason;

    @Column(name = "client_ip")
    private String clientIp;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "attempt_id", nullable = false)
    private String attemptId;

    @Column(name = "duration_ms")
    private Integer durationMs;

    protected AuditOperatorLoginEntity() {}

    public AuditOperatorLoginEntity(String id, OffsetDateTime at, String username, String decision,
                                    String source, String auditReason, String clientIp,
                                    String userAgent, String attemptId, Integer durationMs) {
        this.id = id;
        this.at = at;
        this.username = username;
        this.decision = decision;
        this.source = source;
        this.auditReason = auditReason;
        this.clientIp = clientIp;
        this.userAgent = userAgent;
        this.attemptId = attemptId;
        this.durationMs = durationMs;
    }

    public String getId() { return id; }
    public OffsetDateTime getAt() { return at; }
    public String getUsername() { return username; }
    public String getDecision() { return decision; }
    public String getSource() { return source; }
    public String getAuditReason() { return auditReason; }
    public String getClientIp() { return clientIp; }
    public String getUserAgent() { return userAgent; }
    public String getAttemptId() { return attemptId; }
    public Integer getDurationMs() { return durationMs; }
}
