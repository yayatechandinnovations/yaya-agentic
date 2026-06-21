package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Memberships are the inputs to the access guard, not its outputs. Marked
 * {@link WorkspaceUnscoped} because membership reads are by {@code userId}
 * or by the (userId, workspaceId) PK — the guard predicate itself.
 */
@WorkspaceUnscoped("membership reads ARE the workspace-scoping predicate")
public interface UserMembershipRepository
        extends JpaRepository<UserMembershipEntity, UserMembershipEntity.PK> {

    Optional<UserMembershipEntity> findByUserIdAndWorkspaceId(UUID userId, String workspaceId);

    List<UserMembershipEntity> findByUserId(UUID userId);

    List<UserMembershipEntity> findByWorkspaceId(String workspaceId);

    @Query("select count(m) from UserMembershipEntity m " +
           "where m.workspaceId = :workspaceId and m.role = 'OWNER'")
    long countOwners(@Param("workspaceId") String workspaceId);
}
