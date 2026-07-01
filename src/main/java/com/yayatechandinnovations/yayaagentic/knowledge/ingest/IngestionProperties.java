package com.yayatechandinnovations.yayaagentic.knowledge.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tuning for the async ingestion job queue — bound from
 * {@code yaya.agentic.knowledge.ingestion} (design §9). The poll interval is read
 * directly by the worker's {@code @Scheduled} placeholder; the rest is bound here.
 */
@ConfigurationProperties(prefix = "yaya.agentic.knowledge.ingestion")
public record IngestionProperties(
        Boolean workerEnabled,
        Integer batchSize,
        Integer maxAttempts,
        Duration baseBackoff,
        Duration maxBackoff
) {
    public IngestionProperties {
        if (workerEnabled == null) workerEnabled = true;
        if (batchSize == null || batchSize < 1) batchSize = 4;
        if (maxAttempts == null || maxAttempts < 1) maxAttempts = 5;
        if (baseBackoff == null) baseBackoff = Duration.ofSeconds(30);
        if (maxBackoff == null) maxBackoff = Duration.ofHours(1);
    }

    /** Exponential backoff: base·2^(attempts-1), capped at maxBackoff. */
    public Duration backoffFor(int attempts) {
        int exp = Math.max(0, attempts - 1);
        // Cap the shift so 2^exp never overflows before the min() clamp.
        if (exp >= 20) return maxBackoff;
        Duration d = baseBackoff.multipliedBy(1L << exp);
        return d.compareTo(maxBackoff) > 0 ? maxBackoff : d;
    }
}
