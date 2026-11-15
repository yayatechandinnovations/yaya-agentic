package com.yayatechandinnovations.yayaagentic.headless;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Knobs for headless runs ({@code docs/guides/admin-panel-sessions.md} §17).
 *
 * <ul>
 *   <li>{@code workers} — runs executed at once on this instance (default 2).</li>
 *   <li>{@code queue} — accepted runs that may wait for a worker (default 20);
 *       past it a submit answers {@code 429 busy}.</li>
 *   <li>{@code maxPerTenant} — runs one tenant may have queued or running
 *       (default 2); past it {@code 429 busy}.</li>
 *   <li>{@code perHour} — runs one tenant may submit in an hour (default 20);
 *       past it {@code 429 rate_limited}.</li>
 *   <li>{@code answerTtl} — how long a finished run keeps its answer (default
 *       24 h); {@code rowTtl} — how long the run row is kept (default 30 d).</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "yaya.agentic.headless")
public record HeadlessRunProperties(Integer workers, Integer queue, Integer maxPerTenant, Integer perHour,
                                    Duration answerTtl, Duration rowTtl) {

    public static HeadlessRunProperties defaults() {
        return new HeadlessRunProperties(null, null, null, null, null, null);
    }

    public int workersOrDefault() { return positive(workers, 2); }
    public int queueOrDefault() { return positive(queue, 20); }
    public int maxPerTenantOrDefault() { return positive(maxPerTenant, 2); }
    public int perHourOrDefault() { return positive(perHour, 20); }

    public Duration answerTtlOrDefault() { return positive(answerTtl, Duration.ofHours(24)); }
    public Duration rowTtlOrDefault() { return positive(rowTtl, Duration.ofDays(30)); }

    private static int positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private static Duration positive(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
