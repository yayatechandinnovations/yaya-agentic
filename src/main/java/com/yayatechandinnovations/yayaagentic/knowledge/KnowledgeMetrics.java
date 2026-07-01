package com.yayatechandinnovations.yayaagentic.knowledge;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Micrometer metrics for the knowledge-upload / ingestion pipeline
 * (design §14 step 5). Centralized so tag conventions (tenant, outcome) stay
 * consistent across the upload service, the ingestion orchestrator, and the
 * async worker.
 */
@Component
public class KnowledgeMetrics {

    private final MeterRegistry registry;

    public KnowledgeMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** One or more files accepted and stored. */
    public void uploaded(String tenant, int fileCount, long totalBytes) {
        Counter.builder("knowledge_uploads_total")
                .tags(Tags.of("tenant", safe(tenant)))
                .register(registry)
                .increment(fileCount);
        Counter.builder("knowledge_upload_bytes_total")
                .tags(Tags.of("tenant", safe(tenant)))
                .register(registry)
                .increment(totalBytes);
    }

    /** One ingest run — {@code outcome} is {@code ready} or {@code error}. */
    public void ingest(String tenant, String outcome, Duration elapsed) {
        Timer.builder("knowledge_ingest_duration_seconds")
                .tags(Tags.of("tenant", safe(tenant), "outcome", safe(outcome)))
                .register(registry)
                .record(elapsed);
    }

    /** A queue job reached a terminal/retry state: {@code done|failed|retry}. */
    public void jobOutcome(String tenant, String outcome) {
        Counter.builder("knowledge_ingest_jobs_total")
                .tags(Tags.of("tenant", safe(tenant), "outcome", safe(outcome)))
                .register(registry)
                .increment();
    }

    private static String safe(String s) {
        return s == null ? "unknown" : s;
    }
}
