package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface StepUpAuditRepository extends JpaRepository<StepUpAuditEntity, Long> {

    /** Recent rows for a session — drives admin timeline + cool-off counting. */
    List<StepUpAuditEntity> findByTenantIdAndSessionIdOrderByStartedAtDesc(
            String tenantId, String sessionId);

    /** Cool-off probe — how many failed cycles in the last {@code since}. */
    @WorkspaceUnscoped("session_id is globally unique; tenant filter is enforced upstream by the coordinator")
    @Query("SELECT COUNT(a) FROM StepUpAuditEntity a " +
           "WHERE a.sessionId = :sessionId " +
           "  AND a.startedAt >= :since " +
           "  AND a.outcome LIKE 'failed:%'")
    long countFailedSince(@Param("sessionId") String sessionId,
                          @Param("since") OffsetDateTime since);
}
