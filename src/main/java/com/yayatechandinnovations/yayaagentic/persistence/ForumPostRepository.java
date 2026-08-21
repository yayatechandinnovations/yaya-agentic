package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Forum posts ({@code chat-experience-design.md} §8). */
@WorkspaceUnscoped("public chat surface + tenant-guarded admin; scoping is per entry point")
public interface ForumPostRepository extends JpaRepository<ForumPostEntity, String> {

    /** Visible top-level feed page, pinned first then newest. */
    @Query("select p from ForumPostEntity p where p.entryPointId = :ep "
            + "and p.replyTo is null and p.hidden = false and p.deletedAt is null "
            + "order by p.pinned desc, p.createdAt desc, p.id desc")
    List<ForumPostEntity> feed(@Param("ep") String entryPointId, Pageable page);

    /** Keyset continuation for the unpinned tail of the feed. */
    @Query("select p from ForumPostEntity p where p.entryPointId = :ep "
            + "and p.replyTo is null and p.hidden = false and p.deletedAt is null "
            + "and p.pinned = false "
            + "and (p.createdAt < :createdAt or (p.createdAt = :createdAt and p.id < :id)) "
            + "order by p.createdAt desc, p.id desc")
    List<ForumPostEntity> feedAfter(@Param("ep") String entryPointId,
                                    @Param("createdAt") OffsetDateTime createdAt,
                                    @Param("id") String id,
                                    Pageable page);

    @Query("select p from ForumPostEntity p where p.replyTo = :postId "
            + "and p.hidden = false and p.deletedAt is null "
            + "order by p.createdAt asc, p.id asc")
    List<ForumPostEntity> replies(@Param("postId") String postId, Pageable page);

    /* ---- block-aware variants: drop posts authored by a blocked member (Apple
     *      1.2). System/operator posts have a null accountId and are kept. Used
     *      only when the caller has blocks; otherwise the plain queries run. ---- */

    @Query("select p from ForumPostEntity p where p.entryPointId = :ep "
            + "and p.replyTo is null and p.hidden = false and p.deletedAt is null "
            + "and (p.accountId is null or p.accountId not in :blocked) "
            + "order by p.pinned desc, p.createdAt desc, p.id desc")
    List<ForumPostEntity> feedExcluding(@Param("ep") String entryPointId,
                                        @Param("blocked") Collection<UUID> blocked, Pageable page);

    @Query("select p from ForumPostEntity p where p.entryPointId = :ep "
            + "and p.replyTo is null and p.hidden = false and p.deletedAt is null "
            + "and p.pinned = false "
            + "and (p.accountId is null or p.accountId not in :blocked) "
            + "and (p.createdAt < :createdAt or (p.createdAt = :createdAt and p.id < :id)) "
            + "order by p.createdAt desc, p.id desc")
    List<ForumPostEntity> feedAfterExcluding(@Param("ep") String entryPointId,
                                             @Param("createdAt") OffsetDateTime createdAt,
                                             @Param("id") String id,
                                             @Param("blocked") Collection<UUID> blocked,
                                             Pageable page);

    @Query("select p from ForumPostEntity p where p.replyTo = :postId "
            + "and p.hidden = false and p.deletedAt is null "
            + "and (p.accountId is null or p.accountId not in :blocked) "
            + "order by p.createdAt asc, p.id asc")
    List<ForumPostEntity> repliesExcluding(@Param("postId") String postId,
                                           @Param("blocked") Collection<UUID> blocked, Pageable page);

    /** Admin view: everything including hidden, newest first. */
    List<ForumPostEntity> findByEntryPointIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            String entryPointId, Pageable page);
}
