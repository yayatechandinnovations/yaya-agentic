package com.yayatechandinnovations.yayaagentic.channel.chat;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Fixed-window rate limiting for the public chat front door
 * ({@code chat-channel-design.md} §7.1). The chat surface is internet-facing, so
 * every anonymous session start and message is metered per device, per IP, and
 * per session. {@code anonymousDailyMessageCap} is the LLM-cost circuit breaker.
 *
 * <p>Redis {@code INCR} + {@code EXPIRE} on a window-bucketed key, mirroring the
 * existing {@code StringRedisTemplate} usage. Blocking, but invoked only inside
 * the controller's {@code Mono.fromSupplier} / {@code Flux.defer} boundary —
 * same pattern as the engine call it gates.
 */
@Component
public class ChatRateLimiter {

    private static final String KEY_PREFIX = "yaya:chat:rl:";

    private final StringRedisTemplate redis;
    private final java.time.Clock clock;

    public ChatRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
        this.clock = java.time.Clock.systemUTC();
    }

    /**
     * Record one hit against a fixed window and report whether it is within
     * {@code limit}. The first hit in a window sets the key TTL so buckets
     * self-expire.
     */
    public Decision hit(String dimension, String id, int limit, Duration window) {
        long windowSeconds = Math.max(1, window.toSeconds());
        long nowEpoch = clock.instant().getEpochSecond();
        long windowStart = nowEpoch - (nowEpoch % windowSeconds);
        String key = KEY_PREFIX + dimension + ":" + id + ":" + windowStart;

        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, Duration.ofSeconds(windowSeconds));
        }
        long current = count == null ? 1L : count;
        if (current > limit) {
            long retryAfter = (windowStart + windowSeconds) - nowEpoch;
            return new Decision(false, Math.max(1, retryAfter));
        }
        return new Decision(true, 0);
    }

    public record Decision(boolean allowed, long retryAfterSeconds) {}
}
