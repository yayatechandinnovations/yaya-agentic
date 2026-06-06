package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface RecorderOutboxRepository extends JpaRepository<RecorderOutboxEntity, Long> {

    @Query("""
        select o from RecorderOutboxEntity o
        where o.status = 'PENDING'
          and o.nextAttemptAt <= :now
        order by o.nextAttemptAt asc
        """)
    List<RecorderOutboxEntity> findReadyForDispatch(@Param("now") OffsetDateTime now,
                                                    Pageable pageable);
}
