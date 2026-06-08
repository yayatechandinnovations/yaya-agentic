package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditOperatorLoginRepository extends JpaRepository<AuditOperatorLoginEntity, String> {

    @Query("""
        select a from AuditOperatorLoginEntity a
        where (:username is null or a.username = :username)
          and (:decision is null or a.decision = :decision)
          and (:source   is null or a.source   = :source)
        order by a.at desc
        """)
    Page<AuditOperatorLoginEntity> search(@Param("username") String username,
                                          @Param("decision") String decision,
                                          @Param("source") String source,
                                          Pageable pageable);

    long countByDecisionAndSource(String decision, String source);
}
