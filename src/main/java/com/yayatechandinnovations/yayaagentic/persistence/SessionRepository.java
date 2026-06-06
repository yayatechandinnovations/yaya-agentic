package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
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
}
