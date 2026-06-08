package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * Server-side console operator session. PK is the hex-encoded SHA-256 of
 * the random cookie value — the raw token never lands in the DB.
 *
 * <p>See {@code docs/design/operator-auth-design.md} §6 and §11.</p>
 */
@Entity
@Table(name = "operator_sessions")
public class OperatorSessionEntity {

    @Id
    @Column(name = "id_hash", nullable = false)
    private String idHash;

    @Column(name = "operator_subject", nullable = false)
    private String operatorSubject;

    @Column(name = "operator_display", nullable = false)
    private String operatorDisplay;

    @Column(nullable = false)
    private String source;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "last_seen_at", nullable = false)
    private OffsetDateTime lastSeenAt;

    @Column(name = "client_ip")
    private String clientIp;

    @Column(name = "user_agent")
    private String userAgent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String attributes;

    protected OperatorSessionEntity() {}

    public OperatorSessionEntity(String idHash, String operatorSubject, String operatorDisplay,
                                 String source, OffsetDateTime createdAt, OffsetDateTime expiresAt,
                                 String clientIp, String userAgent, String attributes) {
        this.idHash = idHash;
        this.operatorSubject = operatorSubject;
        this.operatorDisplay = operatorDisplay;
        this.source = source;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.lastSeenAt = createdAt;
        this.clientIp = clientIp;
        this.userAgent = userAgent;
        this.attributes = attributes;
    }

    public String getIdHash() { return idHash; }
    public String getOperatorSubject() { return operatorSubject; }
    public String getOperatorDisplay() { return operatorDisplay; }
    public String getSource() { return source; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public OffsetDateTime getLastSeenAt() { return lastSeenAt; }
    public String getClientIp() { return clientIp; }
    public String getUserAgent() { return userAgent; }
    public String getAttributes() { return attributes; }

    public void setExpiresAt(OffsetDateTime v) { this.expiresAt = v; }
    public void setLastSeenAt(OffsetDateTime v) { this.lastSeenAt = v; }
}
