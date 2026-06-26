package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * One row of {@code channel_session_elevation}. Records a successful
 * step-up: the session's Principal carries the additional scopes/claims
 * recorded here until {@code expires_at}.
 *
 * <p>See {@code whatsapp-channel-auth-design.md} §6.5.
 */
@Entity
@Table(name = "channel_session_elevation")
public class ChannelSessionElevationEntity {

    @Id
    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "elevated_subject", nullable = false, length = 255)
    private String elevatedSubject;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "added_scopes", columnDefinition = "text[]", nullable = false)
    private String[] addedScopes = new String[0];

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "added_claims_json", columnDefinition = "jsonb", nullable = false)
    private String addedClaimsJson = "{}";

    @Column(name = "audience", nullable = false, length = 255)
    private String audience;

    @Column(name = "method", nullable = false, length = 64)
    private String method;

    @Column(name = "minted_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime mintedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "pending_audit_id")
    private Long pendingAuditId;

    protected ChannelSessionElevationEntity() {}

    public ChannelSessionElevationEntity(String sessionId, String tenantId,
                                         String elevatedSubject,
                                         String[] addedScopes,
                                         String addedClaimsJson,
                                         String audience,
                                         String method,
                                         OffsetDateTime expiresAt,
                                         Long pendingAuditId) {
        this.sessionId = sessionId;
        this.tenantId = tenantId;
        this.elevatedSubject = elevatedSubject;
        this.addedScopes = addedScopes == null ? new String[0] : addedScopes;
        this.addedClaimsJson = addedClaimsJson == null ? "{}" : addedClaimsJson;
        this.audience = audience;
        this.method = method;
        this.expiresAt = expiresAt;
        this.pendingAuditId = pendingAuditId;
    }

    public String getSessionId() { return sessionId; }
    public String getTenantId() { return tenantId; }
    public String getElevatedSubject() { return elevatedSubject; }
    public String[] getAddedScopes() { return addedScopes; }
    public String getAddedClaimsJson() { return addedClaimsJson; }
    public String getAudience() { return audience; }
    public String getMethod() { return method; }
    public OffsetDateTime getMintedAt() { return mintedAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public Long getPendingAuditId() { return pendingAuditId; }

    public void setExpiresAt(OffsetDateTime v) { this.expiresAt = v; }
    public void setAddedScopes(String[] v) { this.addedScopes = v; }
    public void setAddedClaimsJson(String v) { this.addedClaimsJson = v; }
}
