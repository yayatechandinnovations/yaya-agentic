package com.yayatechandinnovations.yayaagentic.outreach;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Defense-in-depth on the ingest edge (§4.1 #2): authentication proves *who*;
 * this proves *this exact request, once*. A captured, still-valid request can't
 * be re-fired — the {@code timestamp} must be within the skew window and the
 * {@code nonce} must not have been seen before.
 *
 * <p>Backed by short-TTL Redis keys, mirroring {@code ChatRateLimiter}'s use of
 * {@link StringRedisTemplate}. Blocking, but invoked once per ingest on a
 * bounded-elastic thread.
 */
@Component
public class ReplayGuard {

    /** Max clock skew between the sender's timestamp and our edge. */
    static final Duration SKEW = Duration.ofMinutes(5);
    private static final String KEY_PREFIX = "yaya:outreach:nonce:";

    private final StringRedisTemplate redis;

    public ReplayGuard(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public sealed interface Result {
        record Ok() implements Result {}
        record Rejected(String reason) implements Result {}
    }

    /**
     * @param scope     namespacing key (tenant + sender) so nonces don't collide across senders
     * @param nonce     unique-per-request token from {@code X-Nonce}
     * @param epochMs   the sender's {@code X-Timestamp} in epoch millis
     */
    public Result check(String scope, String nonce, long epochMs) {
        if (nonce == null || nonce.isBlank()) {
            return new Result.Rejected("missing nonce");
        }
        long now = Instant.now().toEpochMilli();
        if (Math.abs(now - epochMs) > SKEW.toMillis()) {
            return new Result.Rejected("timestamp outside skew window");
        }
        String key = KEY_PREFIX + scope + ":" + nonce;
        // setIfAbsent → first request wins; a replay finds the key present.
        Boolean fresh = redis.opsForValue().setIfAbsent(key, "1", SKEW.multipliedBy(2));
        if (!Boolean.TRUE.equals(fresh)) {
            return new Result.Rejected("nonce already used");
        }
        return new Result.Ok();
    }
}
