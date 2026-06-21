package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Recorder-outbox queries are platform-wide by design — the dispatcher
 * processes pending rows for every tenant in one pass.
 */
@WorkspaceUnscoped("recorder outbox dispatcher processes all tenants' events")
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
