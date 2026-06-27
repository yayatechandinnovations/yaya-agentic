package com.yayatechandinnovations.yayaagentic.operator_auth.apikeys;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-IP brute-force defence on failed {@code Authorization: Bearer yk_*}
 * attempts (§11). Default budget 20/min. Only FAILURES drain the bucket — a
 * valid key is never rate-limited here — and once the budget is exhausted every
 * bearer request from that IP is locked out (429) until the bucket refills.
 *
 * <p>v1 storage: {@link ConcurrentHashMap}, single-instance — same trade-off as
 * {@code LoginRateLimiter}. The per-key sustained bucket (§11) is a follow-up.</p>
 */
@Component
public class FailedBearerRateLimiter {

    private final Bandwidth limit;
    private final ConcurrentHashMap<String, Bucket> ipBuckets = new ConcurrentHashMap<>();

    public FailedBearerRateLimiter(ApiKeysProperties props) {
        int perMin = props.failedAuthPerMinutePerIpOrDefault();
        this.limit = Bandwidth.builder()
                .capacity(perMin).refillIntervally(perMin, Duration.ofMinutes(1)).build();
    }

    /** Peek (no consume): false if this IP has exhausted its failed-auth budget. */
    public boolean permitAttempt(String clientIp) {
        return bucket(key(clientIp)).getAvailableTokens() > 0;
    }

    /** Drain one token for a failed attempt. */
    public void recordFailure(String clientIp) {
        bucket(key(clientIp)).tryConsume(1);
    }

    private Bucket bucket(String key) {
        return ipBuckets.computeIfAbsent(key, k -> Bucket.builder().addLimit(limit).build());
    }

    private static String key(String clientIp) {
        return clientIp == null || clientIp.isBlank() ? "_anon_" : clientIp;
    }

    /** Test hook. */
    public void resetAll() { ipBuckets.clear(); }
}
