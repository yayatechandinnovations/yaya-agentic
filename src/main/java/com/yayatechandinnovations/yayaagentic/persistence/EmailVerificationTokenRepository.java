package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.UUID;

@WorkspaceUnscoped("verification tokens are user-scoped, not workspace-scoped")
public interface EmailVerificationTokenRepository
        extends JpaRepository<EmailVerificationTokenEntity, byte[]> {

    @Modifying
    @Query("update EmailVerificationTokenEntity t set t.consumedAt = :now " +
           "where t.tokenHash = :hash and t.consumedAt is null and t.expiresAt > :now")
    int consume(@Param("hash") byte[] hash, @Param("now") OffsetDateTime now);

    @Query("select t.userId from EmailVerificationTokenEntity t where t.tokenHash = :hash")
    UUID lookupUserIdForHash(@Param("hash") byte[] hash);

    @Modifying
    @Query("delete from EmailVerificationTokenEntity t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") OffsetDateTime cutoff);
}
