package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Outbound chat attachments ({@code chat-experience-design.md} §6). Reached only
 * through the public chat surface, which scopes every read by session (and the
 * signed media token); platform-scoped like the sessions they belong to.
 */
@WorkspaceUnscoped("chat attachments are session-scoped end-user uploads on the public chat surface")
public interface ChatAttachmentRepository extends JpaRepository<ChatAttachmentEntity, String> {

    List<ChatAttachmentEntity> findBySessionId(String sessionId);

    /** Unreferenced uploads older than the grace window — the orphan sweep. */
    List<ChatAttachmentEntity> findByTurnIdIsNullAndCreatedAtBefore(OffsetDateTime cutoff);

    /** Sum of bytes an account uploaded since [cutoff] — the daily byte ceiling. */
    @Query("select coalesce(sum(a.sizeBytes), 0) from ChatAttachmentEntity a "
            + "where a.accountId = :accountId and a.createdAt >= :cutoff")
    long bytesUploadedSince(@Param("accountId") UUID accountId,
                            @Param("cutoff") OffsetDateTime cutoff);

    List<ChatAttachmentEntity> findByAccountId(UUID accountId);

    /** Blob dedupe is tenant-wide: only drop bytes when the last row referencing
     *  them is gone. */
    long countByTenantIdAndBlobId(String tenantId, String blobId);
}
