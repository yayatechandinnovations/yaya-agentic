package com.yayatechandinnovations.yayaagentic.knowledge.ingest;

import com.yayatechandinnovations.yayaagentic.knowledge.KnowledgeMetrics;
import com.yayatechandinnovations.yayaagentic.persistence.KnowledgeIngestionJobEntity;
import com.yayatechandinnovations.yayaagentic.persistence.KnowledgeIngestionJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Transactional boundary for the ingestion job queue (design §9). Enqueue is
 * idempotent per {@code (tenant, source, version)} while a job is active;
 * {@link #claimBatch} atomically flips QUEUED→RUNNING under the row locks the
 * {@code FOR UPDATE SKIP LOCKED} query holds, so concurrent workers never
 * double-run a job.
 */
@Service
public class IngestionJobService {

    private static final Logger LOG = LoggerFactory.getLogger(IngestionJobService.class);
    private static final List<String> ACTIVE = List.of("QUEUED", "RUNNING");
    private static final int ERROR_MAX = 4000;

    private final KnowledgeIngestionJobRepository jobs;
    private final IngestionProperties props;
    private final Clock clock;
    private final KnowledgeMetrics metrics;

    public IngestionJobService(KnowledgeIngestionJobRepository jobs,
                               IngestionProperties props, Clock clock,
                               KnowledgeMetrics metrics) {
        this.jobs = jobs;
        this.props = props;
        this.clock = clock;
        this.metrics = metrics;
    }

    /** Queue an ingest for a source version. No-op if one is already pending/running. */
    @Transactional
    public void enqueue(String tenantId, String sourceId, int sourceVersion) {
        if (jobs.existsByTenantIdAndSourceIdAndSourceVersionAndStateIn(
                tenantId, sourceId, sourceVersion, ACTIVE)) {
            return;
        }
        jobs.save(new KnowledgeIngestionJobEntity(
                UUID.randomUUID(), tenantId, sourceId, sourceVersion,
                props.maxAttempts(), OffsetDateTime.now(clock)));
        LOG.debug("enqueued ingestion job for {}/{} v{}", tenantId, sourceId, sourceVersion);
    }

    /**
     * Claim up to {@code limit} runnable jobs, marking each RUNNING and bumping
     * attempts. Runs in one transaction so the SKIP LOCKED locks are released
     * only after the RUNNING flip commits.
     */
    @Transactional
    public List<Claim> claimBatch(int limit) {
        List<KnowledgeIngestionJobEntity> rows = jobs.claimBatch(limit);
        if (rows.isEmpty()) return List.of();
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Claim> claims = new ArrayList<>(rows.size());
        for (KnowledgeIngestionJobEntity e : rows) {
            e.setState("RUNNING");
            e.setAttempts(e.getAttempts() + 1);
            e.setUpdatedAt(now);
            claims.add(new Claim(e.getId(), e.getTenantId(), e.getSourceId(),
                    e.getSourceVersion(), e.getAttempts(), e.getMaxAttempts()));
        }
        jobs.saveAll(rows);
        return claims;
    }

    @Transactional
    public void markDone(UUID jobId) {
        jobs.findById(jobId).ifPresent(e -> {
            e.setState("DONE");
            e.setLastError(null);
            e.setUpdatedAt(OffsetDateTime.now(clock));
            jobs.save(e);
            metrics.jobOutcome(e.getTenantId(), "done");
        });
    }

    /** Re-queue with backoff, or mark FAILED once attempts are exhausted. */
    @Transactional
    public void markFailedOrRetry(UUID jobId, String error) {
        jobs.findById(jobId).ifPresent(e -> {
            OffsetDateTime now = OffsetDateTime.now(clock);
            String outcome;
            if (e.getAttempts() >= e.getMaxAttempts()) {
                e.setState("FAILED");
                outcome = "failed";
                LOG.warn("ingestion job {} FAILED after {} attempts: {}",
                        jobId, e.getAttempts(), error);
            } else {
                e.setState("QUEUED");
                e.setRunAfter(now.plus(props.backoffFor(e.getAttempts())));
                outcome = "retry";
            }
            e.setLastError(truncate(error));
            e.setUpdatedAt(now);
            jobs.save(e);
            metrics.jobOutcome(e.getTenantId(), outcome);
        });
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= ERROR_MAX ? s : s.substring(0, ERROR_MAX);
    }

    /** Detached view of a claimed job handed to the worker. */
    public record Claim(UUID id, String tenantId, String sourceId,
                        int sourceVersion, int attempts, int maxAttempts) {}
}
