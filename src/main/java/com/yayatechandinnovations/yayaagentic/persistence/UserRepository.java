package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Users are an identity table, not workspace-scoped. Marked
 * {@link WorkspaceUnscoped} so the repository-scan test (M5a) doesn't flag
 * its methods.
 */
@WorkspaceUnscoped("users are admin-plane identities; not workspace-scoped")
public interface UserRepository extends JpaRepository<UserEntity, UUID> {

    @Query("select u from UserEntity u where lower(u.email) = lower(:email) and u.deletedAt is null")
    Optional<UserEntity> findActiveByEmail(@Param("email") String email);
}
