package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Audit reads cross workspace boundaries for platform admins (M5b
 * cross-workspace audit screen). Workspace-scoped reads still get a filter
 * argument; the {@link WorkspaceUnscoped} marker just exempts the
 * cross-workspace methods from the scan test.
 */
@WorkspaceUnscoped("platform-admin audit reads intentionally span workspaces")
public interface AuditUserEventRepository extends JpaRepository<AuditUserEventEntity, Long> {

    @Query("select e from AuditUserEventEntity e " +
           "where (:workspaceId is null or e.workspaceId = :workspaceId) " +
           "  and (:userId is null or e.userId = :userId) " +
           "  and (:kind is null or e.kind = :kind) " +
           "  and (:from is null or e.occurredAt >= :from) " +
           "  and (:to is null or e.occurredAt <= :to) " +
           "order by e.occurredAt desc")
    Page<AuditUserEventEntity> search(@Param("workspaceId") String workspaceId,
                                      @Param("userId") UUID userId,
                                      @Param("kind") String kind,
                                      @Param("from") OffsetDateTime from,
                                      @Param("to") OffsetDateTime to,
                                      Pageable pageable);
}
