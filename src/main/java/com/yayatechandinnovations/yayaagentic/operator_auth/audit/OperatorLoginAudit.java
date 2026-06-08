package com.yayatechandinnovations.yayaagentic.operator_auth.audit;

import com.yayatechandinnovations.yayaagentic.persistence.AuditOperatorLoginEntity;
import com.yayatechandinnovations.yayaagentic.persistence.AuditOperatorLoginRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Writes one {@code audit_operator_login} row per attempt — allow or
 * deny, every strategy, every reason. Password is never accepted as a
 * parameter; the writer literally can't log it. See design §10.
 */
@Service
public class OperatorLoginAudit {

    private static final Logger log = LoggerFactory.getLogger(OperatorLoginAudit.class);

    private final AuditOperatorLoginRepository repo;

    public OperatorLoginAudit(AuditOperatorLoginRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public void record(Record r) {
        try {
            AuditOperatorLoginEntity row = new AuditOperatorLoginEntity(
                    UUID.randomUUID().toString(),
                    OffsetDateTime.now(ZoneOffset.UTC),
                    r.username() == null ? "" : r.username(),
                    r.decision().name(),
                    r.source(),
                    r.auditReason(),
                    r.clientIp(),
                    r.userAgent(),
                    r.attemptId() == null ? UUID.randomUUID().toString() : r.attemptId(),
                    r.durationMs() == null ? null : Math.toIntExact(Math.min(r.durationMs(), Integer.MAX_VALUE)));
            repo.save(row);
        } catch (Exception e) {
            // Audit-write failures must NOT break the login path itself.
            // Log loudly so monitoring picks it up.
            log.error("operator-login audit write failed for user={} decision={} attempt={}",
                    r.username(), r.decision(), r.attemptId(), e);
        }
    }

    public enum Decision { ALLOW, DENY }

    public record Record(
            String username,
            Decision decision,
            String source,            // BOOTSTRAP | HTTP_DELEGATE | null
            String auditReason,       // null on ALLOW
            String clientIp,
            String userAgent,
            String attemptId,
            Long durationMs
    ) {}
}
