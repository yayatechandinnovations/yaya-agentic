package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** The end-user receipt wallet ({@code chat-experience-design.md} §7.5). */
@WorkspaceUnscoped("wallet rows are read only by their owning verified account on the public chat surface")
public interface ChatReceiptRepository extends JpaRepository<ChatReceiptEntity, String> {

    List<ChatReceiptEntity> findByAccountIdOrderByCreatedAtDescIdDesc(UUID accountId, Pageable page);

    /** Keyset page: strictly older than the cursor row. */
    @Query("select r from ChatReceiptEntity r where r.accountId = :accountId "
            + "and (r.createdAt < :createdAt or (r.createdAt = :createdAt and r.id < :id)) "
            + "order by r.createdAt desc, r.id desc")
    List<ChatReceiptEntity> pageAfter(@Param("accountId") UUID accountId,
                                      @Param("createdAt") OffsetDateTime createdAt,
                                      @Param("id") String id,
                                      Pageable page);

    void deleteByAccountId(UUID accountId);
}
