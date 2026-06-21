package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Invitations are looked up by token (anonymous accept path) or by email
 * (signup-time auto-redemption) — both flows cross workspace boundaries by
 * design. The workspace-scoped reads (list pending invitations for a
 * workspace) DO filter by {@code workspace_id}, but the read happens after
 * the access guard runs in the controller, so the filter argument is
 * already trustworthy.
 */
@WorkspaceUnscoped("token-bearer + email lookup paths intentionally cross workspaces")
public interface UserInvitationRepository extends JpaRepository<UserInvitationEntity, UUID> {

    @Query("select i from UserInvitationEntity i " +
           "where i.tokenHash = :hash and i.consumedAt is null")
    Optional<UserInvitationEntity> findPendingByTokenHash(@Param("hash") byte[] hash);

    @Query("select i from UserInvitationEntity i " +
           "where lower(i.email) = lower(:email) and i.consumedAt is null and i.expiresAt > current_timestamp")
    List<UserInvitationEntity> findPendingByEmail(@Param("email") String email);

    List<UserInvitationEntity> findByWorkspaceIdAndConsumedAtIsNull(String workspaceId);
}
