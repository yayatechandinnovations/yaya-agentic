package com.yayatechandinnovations.yayaagentic.operator_auth.session;

import com.yayatechandinnovations.yayaagentic.persistence.OperatorSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Periodically prunes expired rows from {@code operator_sessions}. The
 * filter already treats an expired session as logged-out at lookup time;
 * this job just keeps the table from accumulating dead rows.
 *
 * <p>Runs every 10 minutes via {@code @EnableScheduling} (already on
 * {@code CoreConfiguration}). Single-instance only — when we go
 * multi-instance, swap to a leader-elected scheduler.</p>
 */
@Component
public class OperatorSessionCleanup {

    private static final Logger log = LoggerFactory.getLogger(OperatorSessionCleanup.class);

    private final OperatorSessionRepository repo;

    public OperatorSessionCleanup(OperatorSessionRepository repo) {
        this.repo = repo;
    }

    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT1M")
    @Transactional
    public void purgeExpired() {
        int removed = repo.deleteExpired(OffsetDateTime.now(ZoneOffset.UTC));
        if (removed > 0) {
            log.info("operator-session cleanup: purged {} expired rows", removed);
        }
    }
}
