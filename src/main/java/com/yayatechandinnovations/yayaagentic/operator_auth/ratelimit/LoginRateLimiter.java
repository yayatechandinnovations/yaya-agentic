package com.yayatechandinnovations.yayaagentic.operator_auth.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Two-axis token-bucket guard for {@code POST /v1/auth/login}:
 * <ul>
 *   <li>per-username: 5 attempts / minute</li>
 *   <li>per-IP:       30 attempts / minute</li>
 * </ul>
 *
 * <p>Each login attempt consumes one token from BOTH buckets. Denial on
 * either axis returns 429 with {@code Retry-After}. Successful logins do
 * not refund; buckets refill at the bandwidth rate.</p>
 *
 * <p>v1 storage: {@link ConcurrentHashMap}, single-instance. Determined
 * spammers can grow either map without bound — a follow-up should bound
 * via Caffeine or move to bucket4j-redis. The cost is bounded by
 * spammer-controlled cardinality; not worth solving before a real
 * deployment hits it.</p>
 */
@Component
public class LoginRateLimiter {

    private static final Bandwidth USER_LIMIT = Bandwidth.builder()
            .capacity(5).refillIntervally(5, Duration.ofMinutes(1)).build();
    private static final Bandwidth IP_LIMIT = Bandwidth.builder()
            .capacity(30).refillIntervally(30, Duration.ofMinutes(1)).build();

    private final ConcurrentHashMap<String, Bucket> userBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Bucket> ipBuckets = new ConcurrentHashMap<>();

    /**
     * @return an {@link Outcome.Allowed} or {@link Outcome.Denied} carrying
     *         the seconds the caller should wait before retrying.
     */
    public Outcome check(String username, String clientIp) {
        String userKey = username == null || username.isBlank() ? "_anon_" : username;
        String ipKey = clientIp == null || clientIp.isBlank() ? "_anon_" : clientIp;

        Bucket userBucket = userBuckets.computeIfAbsent(userKey, k ->
                Bucket.builder().addLimit(USER_LIMIT).build());
        ConsumptionProbe userProbe = userBucket.tryConsumeAndReturnRemaining(1);
        if (!userProbe.isConsumed()) {
            return new Outcome.Denied("username", nanosToSecondsCeil(userProbe.getNanosToWaitForRefill()));
        }

        Bucket ipBucket = ipBuckets.computeIfAbsent(ipKey, k ->
                Bucket.builder().addLimit(IP_LIMIT).build());
        ConsumptionProbe ipProbe = ipBucket.tryConsumeAndReturnRemaining(1);
        if (!ipProbe.isConsumed()) {
            return new Outcome.Denied("ip", nanosToSecondsCeil(ipProbe.getNanosToWaitForRefill()));
        }

        return new Outcome.Allowed();
    }

    private static long nanosToSecondsCeil(long nanos) {
        return Math.max(1, (nanos + 999_999_999L) / 1_000_000_000L);
    }

    /** Test-only escape hatch: wipes both bucket maps. Production code
     *  doesn't need this — buckets refill at their bandwidth rate. */
    public void resetAll() {
        userBuckets.clear();
        ipBuckets.clear();
    }

    public sealed interface Outcome {
        record Allowed() implements Outcome {}
        record Denied(String axis, long retryAfterSeconds) implements Outcome {}
    }
}
