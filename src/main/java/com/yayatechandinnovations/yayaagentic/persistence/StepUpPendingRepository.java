package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface StepUpPendingRepository extends JpaRepository<StepUpPendingEntity, Long> {

    /** Open pending for (session, audience) — at most one per the partial
     *  unique index in V40. Session id is globally unique. */
    @WorkspaceUnscoped("session_id is globally unique; tenant_id filter is enforced upstream by the worker")
    @Query("SELECT p FROM StepUpPendingEntity p " +
           "WHERE p.sessionId = :sessionId " +
           "  AND p.audience = :audience " +
           "  AND p.completion IS NULL")
    Optional<StepUpPendingEntity> findOpenForAudience(@Param("sessionId") String sessionId,
                                                      @Param("audience") String audience);

    /** Any open pending for the session — used when the worker can't yet
     *  bind the inbound to a specific audience. */
    @WorkspaceUnscoped("session_id is globally unique")
    @Query("SELECT p FROM StepUpPendingEntity p " +
           "WHERE p.sessionId = :sessionId " +
           "  AND p.completion IS NULL " +
           "ORDER BY p.startedAt DESC")
    List<StepUpPendingEntity> findAllOpenForSession(@Param("sessionId") String sessionId);

    /** Background-job housekeeping; sweeps across tenants. */
    @WorkspaceUnscoped("housekeeping — sweep across tenants by expiry alone")
    @Query("SELECT p FROM StepUpPendingEntity p " +
           "WHERE p.completion IS NULL AND p.expiresAt < :cutoff")
    List<StepUpPendingEntity> findExpiredOpen(@Param("cutoff") OffsetDateTime cutoff);

    /** Look up the open deep-link pending row matching the JTI carried by the
     *  callback's JWT. JTIs are globally unique. */
    @WorkspaceUnscoped("deep_link_jti is globally unique (UUID v4 minted by yaya-agentic)")
    @Query("SELECT p FROM StepUpPendingEntity p " +
           "WHERE p.deepLinkJti = :jti " +
           "  AND p.completion IS NULL")
    Optional<StepUpPendingEntity> findOpenByDeepLinkJti(@Param("jti") String jti);
}
