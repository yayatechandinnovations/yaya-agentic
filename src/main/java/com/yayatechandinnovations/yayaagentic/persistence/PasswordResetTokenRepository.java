package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.UUID;

@WorkspaceUnscoped("password-reset tokens are user-scoped, not workspace-scoped")
public interface PasswordResetTokenRepository
        extends JpaRepository<PasswordResetTokenEntity, byte[]> {

    @Modifying
    @Query("update PasswordResetTokenEntity t set t.consumedAt = :now " +
           "where t.tokenHash = :hash and t.consumedAt is null and t.expiresAt > :now")
    int consume(@Param("hash") byte[] hash, @Param("now") OffsetDateTime now);

    @Query("select t.userId from PasswordResetTokenEntity t where t.tokenHash = :hash")
    UUID lookupUserIdForHash(@Param("hash") byte[] hash);

    @Modifying
    @Query("delete from PasswordResetTokenEntity t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") OffsetDateTime cutoff);
}
