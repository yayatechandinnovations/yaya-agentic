package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionRepository extends JpaRepository<SessionEntity, UUID> {

    @Query("""
        select s from SessionEntity s
        where s.tenantId = :tenantId
          and (:principalSubject is null or s.principalSubject = :principalSubject)
          and (:profileId         is null or s.profileId = :profileId)
          and (:from              is null or s.createdAt >= :from)
          and (:to                is null or s.createdAt <  :to)
        """)
    Page<SessionEntity> search(@Param("tenantId") String tenantId,
                               @Param("principalSubject") String principalSubject,
                               @Param("profileId") String profileId,
                               @Param("from") OffsetDateTime from,
                               @Param("to") OffsetDateTime to,
                               Pageable pageable);

    /** Annotate a freshly-started chat session with its account + entry point so
     *  it can be found for resume (chat-account-self-service-design.md §5.3). Only
     *  touches two index columns — the recorder still owns the row's lifecycle. */
    @Modifying
    @Query("update SessionEntity s set s.chatAccountId = :accountId, s.entryPointId = :entryPointId "
            + "where s.id = :id")
    int linkAccount(@Param("id") UUID id,
                    @Param("accountId") UUID accountId,
                    @Param("entryPointId") String entryPointId);

    /** The resume candidate: the most recent session this account held at this
     *  entry point (§7.2). */
    Optional<SessionEntity> findFirstByChatAccountIdAndEntryPointIdOrderByCreatedAtDesc(
            UUID chatAccountId, String entryPointId);

    /** Every session for an account — drives GDPR erasure on account delete (§6.3). */
    List<SessionEntity> findByChatAccountId(UUID chatAccountId);
}
