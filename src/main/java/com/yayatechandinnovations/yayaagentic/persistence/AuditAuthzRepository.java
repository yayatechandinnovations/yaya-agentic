package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditAuthzRepository extends JpaRepository<AuditAuthzEntity, Long> {

    @Query("""
        select a from AuditAuthzEntity a
        where a.tenantId = :tenantId
          and (:decision is null or a.decision = :decision)
          and (:principal is null or a.principalSubject = :principal)
          and (:toolId    is null or a.toolId = :toolId)
        order by a.createdAt desc
        """)
    Page<AuditAuthzEntity> search(@Param("tenantId") String tenantId,
                                  @Param("decision") String decision,
                                  @Param("principal") String principal,
                                  @Param("toolId") String toolId,
                                  Pageable pageable);

    @Query("""
        select a from AuditAuthzEntity a
        where a.sessionId = :sessionId and a.decision = 'DENY'
        order by a.createdAt desc
        """)
    Page<AuditAuthzEntity> latestDenialForSession(@Param("sessionId") java.util.UUID sessionId,
                                                  Pageable pageable);
}
