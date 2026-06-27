package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Key-centric audit row. Exactly one of {@code clientIp} / {@code clientIpHash}
 * is populated, enforced both by the DB CHECK and by {@code AuditIpRecorder}:
 * USE_DENIED stores the full IP (security-relevant); state changes store a
 * salted hash. See {@code docs/design/api-keys-design.md} §12.
 */
@Entity
@Table(name = "audit_api_key_events")
public class AuditApiKeyEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false)
    private Long id;

    @Column(name = "workspace_id", length = 64)
    private String workspaceId;

    @Column(name = "api_key_id")
    private UUID apiKeyId;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(nullable = false)
    private String kind;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail_json", columnDefinition = "jsonb")
    private String detailJson;

    @Column(name = "client_ip")
    private String clientIp;

    @Column(name = "client_ip_hash")
    private byte[] clientIpHash;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    protected AuditApiKeyEventEntity() {}

    public AuditApiKeyEventEntity(String workspaceId, UUID apiKeyId, UUID actorUserId,
                                  String kind, String detailJson, String clientIp,
                                  byte[] clientIpHash, String userAgent, OffsetDateTime occurredAt) {
        this.workspaceId = workspaceId;
        this.apiKeyId = apiKeyId;
        this.actorUserId = actorUserId;
        this.kind = kind;
        this.detailJson = detailJson;
        this.clientIp = clientIp;
        this.clientIpHash = clientIpHash;
        this.userAgent = userAgent;
        this.occurredAt = occurredAt;
    }

    public Long getId() { return id; }
    public String getWorkspaceId() { return workspaceId; }
    public UUID getApiKeyId() { return apiKeyId; }
    public UUID getActorUserId() { return actorUserId; }
    public String getKind() { return kind; }
    public String getDetailJson() { return detailJson; }
    public String getClientIp() { return clientIp; }
    public byte[] getClientIpHash() { return clientIpHash; }
    public String getUserAgent() { return userAgent; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
}
