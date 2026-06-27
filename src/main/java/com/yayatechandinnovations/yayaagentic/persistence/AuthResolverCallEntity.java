package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of {@code auth_resolver_calls} — the audit trail for every
 * resolver dispatch, including cache hits. {@code caller_sha256} is the
 * SHA-256 of the lower-hex E.164 phone (same namespace as the Redis cache
 * key); raw phones are never stored here.
 *
 * <p>See {@code docs/design/channel-auth-resolver-design.md} §12.
 */
@Entity
@Table(name = "auth_resolver_calls")
public class AuthResolverCallEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "resolver_name", nullable = false, length = 64)
    private String resolverName;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "sender_id", nullable = false, length = 64)
    private String senderId;

    @Column(name = "caller_sha256", nullable = false, length = 64)
    private String callerSha256;

    @Column(nullable = false, length = 32)
    private String channel;

    @Column(nullable = false, length = 16)
    private String reason;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "elapsed_ms")
    private Integer elapsedMs;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "cache_state", nullable = false, length = 16)
    private String cacheState = "MISS";

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "error_summary", length = 512)
    private String errorSummary;

    protected AuthResolverCallEntity() {}

    public AuthResolverCallEntity(String tenantId, String resolverName, UUID sessionId,
                                  String senderId, String callerSha256, String channel,
                                  String reason, OffsetDateTime startedAt, String status,
                                  String cacheState) {
        this.tenantId = tenantId;
        this.resolverName = resolverName;
        this.sessionId = sessionId;
        this.senderId = senderId;
        this.callerSha256 = callerSha256;
        this.channel = channel;
        this.reason = reason;
        this.startedAt = startedAt;
        this.status = status;
        this.cacheState = cacheState;
    }

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getResolverName() { return resolverName; }
    public UUID getSessionId() { return sessionId; }
    public String getSenderId() { return senderId; }
    public String getCallerSha256() { return callerSha256; }
    public String getChannel() { return channel; }
    public String getReason() { return reason; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public Integer getElapsedMs() { return elapsedMs; }
    public String getStatus() { return status; }
    public String getCacheState() { return cacheState; }
    public Integer getHttpStatus() { return httpStatus; }
    public String getErrorSummary() { return errorSummary; }

    public void setElapsedMs(Integer v) { this.elapsedMs = v; }
    public void setStatus(String v) { this.status = v; }
    public void setCacheState(String v) { this.cacheState = v; }
    public void setHttpStatus(Integer v) { this.httpStatus = v; }
    public void setErrorSummary(String v) { this.errorSummary = v; }
}
