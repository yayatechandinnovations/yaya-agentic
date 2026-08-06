package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Forum reports ({@code chat-experience-design.md} §8.2). */
@WorkspaceUnscoped("reports belong to verified chat accounts; resolved via tenant-guarded admin")
public interface ForumReportRepository extends JpaRepository<ForumReportEntity, String> {

    long countByPostIdAndResolvedAtIsNull(String postId);

    Optional<ForumReportEntity> findByPostIdAndAccountIdAndResolvedAtIsNull(String postId, UUID accountId);

    List<ForumReportEntity> findByResolvedAtIsNullOrderByCreatedAtAsc(Pageable page);

    List<ForumReportEntity> findByPostIdAndResolvedAtIsNull(String postId);
}
