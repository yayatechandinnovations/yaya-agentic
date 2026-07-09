package com.yayatechandinnovations.yayaidentity.platform.audit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class JpaAuditLog implements AuditLog {

    private final AuditLogRepository repo;

    public JpaAuditLog(AuditLogRepository repo) {
        this.repo = repo;
    }

    // REQUIRES_NEW: an audit write must survive even if the surrounding business
    // transaction rolls back — a denied action is still a fact worth recording.
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditEntry entry) {
        repo.save(new AuditLogEntity(entry, Instant.now()));
    }
}
