package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * One row of {@code step_up_pending} — an in-flight verification flow. At
 * most one open ({@code completion IS NULL}) row exists per
 * {@code (session_id, audience)}; the partial unique index in V40 enforces.
 *
 * <p>{@code code_hash_b64} carries the bcrypt of the OTP code (NOT the code
 * itself). {@code deep_link_jti} carries the issued JWT id for the future
 * deep-link variant (W-A6).
 *
 * <p>See {@code whatsapp-channel-auth-design.md} §6.6.
 */
@Entity
@Table(name = "step_up_pending")
public class StepUpPendingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "audience", nullable = false, length = 255)
    private String audience;

    @Column(name = "method", nullable = false, length = 64)
    private String method;

    @Column(name = "code_hash_b64", length = 255)
    private String codeHashB64;

    @Column(name = "deep_link_jti", length = 64)
    private String deepLinkJti;

    @Column(name = "target_address", nullable = false, length = 255)
    private String targetAddress;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 5;

    @Column(name = "started_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime startedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    /** {@code null} when open; otherwise one of: verified | expired | exhausted | aborted */
    @Column(length = 16)
    private String completion;

    @Column(name = "elevated_subject", length = 255)
    private String elevatedSubject;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "added_scopes", columnDefinition = "text[]")
    private String[] addedScopes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "added_claims_json", columnDefinition = "jsonb")
    private String addedClaimsJson;

    protected StepUpPendingEntity() {}

    public StepUpPendingEntity(String sessionId, String tenantId,
                               String audience, String method,
                               String codeHashB64, String deepLinkJti,
                               String targetAddress, int maxAttempts,
                               OffsetDateTime expiresAt) {
        this.sessionId = sessionId;
        this.tenantId = tenantId;
        this.audience = audience;
        this.method = method;
        this.codeHashB64 = codeHashB64;
        this.deepLinkJti = deepLinkJti;
        this.targetAddress = targetAddress;
        this.maxAttempts = maxAttempts;
        this.expiresAt = expiresAt;
    }

    public Long getId() { return id; }
    public String getSessionId() { return sessionId; }
    public String getTenantId() { return tenantId; }
    public String getAudience() { return audience; }
    public String getMethod() { return method; }
    public String getCodeHashB64() { return codeHashB64; }
    public String getDeepLinkJti() { return deepLinkJti; }
    public String getTargetAddress() { return targetAddress; }
    public int getAttempts() { return attempts; }
    public int getMaxAttempts() { return maxAttempts; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public String getCompletion() { return completion; }
    public String getElevatedSubject() { return elevatedSubject; }
    public String[] getAddedScopes() { return addedScopes; }
    public String getAddedClaimsJson() { return addedClaimsJson; }

    public void setAttempts(int v) { this.attempts = v; }
    public void setCompletedAt(OffsetDateTime v) { this.completedAt = v; }
    public void setCompletion(String v) { this.completion = v; }
    public void setElevatedSubject(String v) { this.elevatedSubject = v; }
    public void setAddedScopes(String[] v) { this.addedScopes = v; }
    public void setAddedClaimsJson(String v) { this.addedClaimsJson = v; }
}
