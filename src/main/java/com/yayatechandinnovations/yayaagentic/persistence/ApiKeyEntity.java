package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A workspace-scoped API key. {@code secretHash} is sha256(secret); the raw
 * secret is returned exactly once on create and never persisted. See
 * {@code docs/design/api-keys-design.md} §7.
 *
 * <p>{@code workspaceId} is the slug PK of {@code tenants} (VARCHAR), matching
 * {@code user_workspace_memberships} — the design's UUID assumed a cutover that
 * has not landed.</p>
 */
@Entity
@Table(name = "api_keys")
public class ApiKeyEntity {

    @Id
    @Column(nullable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false, length = 64)
    private String workspaceId;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(name = "key_prefix", nullable = false, length = 16)
    private String keyPrefix;

    @Column(name = "secret_hash", nullable = false)
    private byte[] secretHash;

    @Column(nullable = false, length = 32)
    private String role;

    @Column(name = "created_by_user_id")
    private UUID createdByUserId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @Column(name = "last_used_at")
    private OffsetDateTime lastUsedAt;

    @Column(name = "last_used_ip")
    private String lastUsedIp;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @Column(name = "revoked_by_user_id")
    private UUID revokedByUserId;

    /** Platform-scope key: acts with platform-admin reach across every
     *  workspace (one-click provisioning design, gap B). Mintable only
     *  by platform-admin users — enforced in ApiKeyService. */
    @Column(name = "platform_scope", nullable = false)
    private boolean platformScope;

    protected ApiKeyEntity() {}

    public ApiKeyEntity(UUID id, String workspaceId, String name, String keyPrefix,
                        byte[] secretHash, String role, UUID createdByUserId,
                        OffsetDateTime createdAt, OffsetDateTime expiresAt) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.name = name;
        this.keyPrefix = keyPrefix;
        this.secretHash = secretHash;
        this.role = role;
        this.createdByUserId = createdByUserId;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public UUID getId() { return id; }
    public String getWorkspaceId() { return workspaceId; }
    public String getName() { return name; }
    public String getKeyPrefix() { return keyPrefix; }
    public byte[] getSecretHash() { return secretHash; }
    public String getRole() { return role; }
    public UUID getCreatedByUserId() { return createdByUserId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public OffsetDateTime getLastUsedAt() { return lastUsedAt; }
    public String getLastUsedIp() { return lastUsedIp; }
    public OffsetDateTime getRevokedAt() { return revokedAt; }
    public UUID getRevokedByUserId() { return revokedByUserId; }
    public boolean isPlatformScope() { return platformScope; }
    public void setPlatformScope(boolean platformScope) { this.platformScope = platformScope; }

    public void setName(String name) { this.name = name; }
    public void setLastUsedAt(OffsetDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public void setLastUsedIp(String lastUsedIp) { this.lastUsedIp = lastUsedIp; }
    public void setRevokedAt(OffsetDateTime revokedAt) { this.revokedAt = revokedAt; }
    public void setRevokedByUserId(UUID revokedByUserId) { this.revokedByUserId = revokedByUserId; }
}
