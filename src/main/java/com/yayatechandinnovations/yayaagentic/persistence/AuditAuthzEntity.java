package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "audit_authz")
public class AuditAuthzEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "turn_id")
    private UUID turnId;

    @Column(name = "principal_subject", length = 255)
    private String principalSubject;

    @Column(name = "tool_id", length = 128)
    private String toolId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "args_json", columnDefinition = "jsonb")
    private String argsJson;

    @Column(nullable = false, length = 8)
    private String decision;

    @Column(name = "user_reason")
    private String userReason;

    @Column(name = "audit_reason")
    private String auditReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "policy_trace_json", columnDefinition = "jsonb", nullable = false)
    private String policyTraceJson = "{}";

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected AuditAuthzEntity() {}

    public AuditAuthzEntity(String tenantId, UUID sessionId, UUID turnId,
                            String principalSubject, String toolId,
                            String argsJson, String decision,
                            String userReason, String auditReason,
                            String policyTraceJson) {
        this.tenantId = tenantId;
        this.sessionId = sessionId;
        this.turnId = turnId;
        this.principalSubject = principalSubject;
        this.toolId = toolId;
        this.argsJson = argsJson;
        this.decision = decision;
        this.userReason = userReason;
        this.auditReason = auditReason;
        this.policyTraceJson = policyTraceJson;
    }

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public UUID getSessionId() { return sessionId; }
    public UUID getTurnId() { return turnId; }
    public String getPrincipalSubject() { return principalSubject; }
    public String getToolId() { return toolId; }
    public String getArgsJson() { return argsJson; }
    public String getDecision() { return decision; }
    public String getUserReason() { return userReason; }
    public String getAuditReason() { return auditReason; }
    public String getPolicyTraceJson() { return policyTraceJson; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
