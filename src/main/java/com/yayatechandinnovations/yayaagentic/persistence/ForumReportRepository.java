package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Forum reports ({@code chat-experience-design.md} §8.2). */
@WorkspaceUnscoped("reports belong to verified chat accounts; resolved via tenant-guarded admin")
public interface ForumReportRepository extends JpaRepository<ForumReportEntity, String> {

    long countByPostIdAndResolvedAtIsNull(String postId);

    Optional<ForumReportEntity> findByPostIdAndAccountIdAndResolvedAtIsNull(String postId, UUID accountId);

    /** Open reports on one tenant's posts — reports carry no tenant of their own. */
    @Query("select r from ForumReportEntity r where r.resolvedAt is null and r.postId in "
            + "(select p.id from ForumPostEntity p where p.tenantId = :tenantId) order by r.createdAt asc")
    List<ForumReportEntity> findOpenForTenant(@Param("tenantId") String tenantId, Pageable page);

    List<ForumReportEntity> findByPostIdAndResolvedAtIsNull(String postId);
}
