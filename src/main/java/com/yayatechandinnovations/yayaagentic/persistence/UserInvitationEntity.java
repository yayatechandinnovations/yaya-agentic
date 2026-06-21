package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Pending or consumed workspace invitation. See V20 and
 * {@code docs/specs/user-accounts/Backend-Data.md} §2.3.
 *
 * <p>tokenHash is sha256(raw) — the raw token only ever appears in the
 * outbound email body.</p>
 */
@Entity
@Table(name = "user_invitations")
public class UserInvitationEntity {

    @Id
    @Column(nullable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false, length = 64)
    private String workspaceId;

    @Column(nullable = false, columnDefinition = "citext")
    private String email;

    @Column(nullable = false)
    private String role;

    @Column(name = "token_hash", nullable = false)
    private byte[] tokenHash;

    @Column(name = "invited_by", nullable = false)
    private UUID invitedBy;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "consumed_at")
    private OffsetDateTime consumedAt;

    @Column(name = "consumed_by")
    private UUID consumedBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected UserInvitationEntity() {}

    public UserInvitationEntity(UUID id, String workspaceId, String email, String role,
                                byte[] tokenHash, UUID invitedBy,
                                OffsetDateTime expiresAt, OffsetDateTime createdAt) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.email = email;
        this.role = role;
        this.tokenHash = tokenHash;
        this.invitedBy = invitedBy;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public String getWorkspaceId() { return workspaceId; }
    public String getEmail() { return email; }
    public String getRole() { return role; }
    public byte[] getTokenHash() { return tokenHash; }
    public UUID getInvitedBy() { return invitedBy; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public OffsetDateTime getConsumedAt() { return consumedAt; }
    public UUID getConsumedBy() { return consumedBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public void setConsumedAt(OffsetDateTime consumedAt) { this.consumedAt = consumedAt; }
    public void setConsumedBy(UUID consumedBy) { this.consumedBy = consumedBy; }
}
