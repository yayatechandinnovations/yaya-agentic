package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * (user, workspace) → role edge. The single load-bearing predicate the
 * access guard consults. See V20 migration and
 * {@code docs/specs/user-accounts/Backend-Data.md} §2.2.
 *
 * <p>workspaceId is a VARCHAR(64) reference into {@code tenants.id} for
 * now; V23 sweeps it to UUID along with every other child FK.</p>
 */
@Entity
@Table(name = "user_workspace_memberships")
@IdClass(UserMembershipEntity.PK.class)
public class UserMembershipEntity {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Id
    @Column(name = "workspace_id", nullable = false, length = 64)
    private String workspaceId;

    @Column(nullable = false)
    private String role;

    @Column(name = "added_by")
    private UUID addedBy;

    @Column(name = "added_at", nullable = false)
    private OffsetDateTime addedAt;

    protected UserMembershipEntity() {}

    public UserMembershipEntity(UUID userId, String workspaceId, String role,
                                UUID addedBy, OffsetDateTime addedAt) {
        this.userId = userId;
        this.workspaceId = workspaceId;
        this.role = role;
        this.addedBy = addedBy;
        this.addedAt = addedAt;
    }

    public UUID getUserId() { return userId; }
    public String getWorkspaceId() { return workspaceId; }
    public String getRole() { return role; }
    public UUID getAddedBy() { return addedBy; }
    public OffsetDateTime getAddedAt() { return addedAt; }

    public void setRole(String role) { this.role = role; }

    public static class PK implements Serializable {
        private UUID userId;
        private String workspaceId;

        public PK() {}
        public PK(UUID userId, String workspaceId) {
            this.userId = userId;
            this.workspaceId = workspaceId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(userId, pk.userId) && Objects.equals(workspaceId, pk.workspaceId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, workspaceId);
        }
    }
}
