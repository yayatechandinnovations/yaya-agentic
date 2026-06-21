package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@WorkspaceUnscoped("mail outbox is platform-wide; not workspace-scoped")
public interface EmailOutboxRepository extends JpaRepository<EmailOutboxEntity, Long> {

    Optional<EmailOutboxEntity> findByIdempotencyKey(String idempotencyKey);

    @Query("select e from EmailOutboxEntity e " +
           "where e.status in ('PENDING','RETRYING') and e.nextAttemptAt <= :now " +
           "order by e.nextAttemptAt asc")
    List<EmailOutboxEntity> findReady(@Param("now") OffsetDateTime now, Pageable pageable);
}
