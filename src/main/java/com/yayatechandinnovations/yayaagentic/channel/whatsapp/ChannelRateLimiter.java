package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token-bucket rate limiters for the inbound webhook path. Two scopes:
 *
 * <ul>
 *   <li>Per-sender: caps how many webhooks Twilio can fire for a single
 *       sender. Default 100 messages per second.</li>
 *   <li>Per-end-user-on-sender: caps how many inbound messages a single end
 *       user can fire on a sender. Default 20 messages per minute.</li>
 * </ul>
 *
 * <p>Buckets are in-memory; a multi-instance deployment behind a load
 * balancer needs a shared bucket store. v1 ships a single-instance bucket
 * with the SPI seam shaped so a Redis-backed variant slots in by re-binding
 * the bean.
 *
 * <p>See {@code whatsapp-channel-design.md} §16 rate limiting bullet.
 */
@Component
public class ChannelRateLimiter {

    private final long senderTokensPerSecond;
    private final long senderBucketSize;
    private final long endUserTokensPerMinute;
    private final long endUserBucketSize;

    private final Map<String, Bucket> senderBuckets = new ConcurrentHashMap<>();
    private final Map<String, Bucket> endUserBuckets = new ConcurrentHashMap<>();

    public ChannelRateLimiter(
            @Value("${yaya.agentic.channels.whatsapp.rate-limit.sender-per-second:100}")
                    long senderTokensPerSecond,
            @Value("${yaya.agentic.channels.whatsapp.rate-limit.sender-burst:200}")
                    long senderBucketSize,
            @Value("${yaya.agentic.channels.whatsapp.rate-limit.end-user-per-minute:20}")
                    long endUserTokensPerMinute,
            @Value("${yaya.agentic.channels.whatsapp.rate-limit.end-user-burst:30}")
                    long endUserBucketSize) {
        this.senderTokensPerSecond = senderTokensPerSecond;
        this.senderBucketSize = senderBucketSize;
        this.endUserTokensPerMinute = endUserTokensPerMinute;
        this.endUserBucketSize = endUserBucketSize;
    }

    /** Returns true if the inbound is admitted under per-sender limits. */
    public boolean allowSender(String senderId) {
        Bucket b = senderBuckets.computeIfAbsent(senderId,
                k -> new Bucket(senderBucketSize, senderBucketSize, senderTokensPerSecond,
                        Duration.ofSeconds(1)));
        return b.tryConsume();
    }

    /** Returns true if the inbound is admitted under per-end-user limits. */
    public boolean allowEndUser(String senderId, String endUserPhone) {
        String key = senderId + "|" + endUserPhone;
        Bucket b = endUserBuckets.computeIfAbsent(key,
                k -> new Bucket(endUserBucketSize, endUserBucketSize, endUserTokensPerMinute,
                        Duration.ofMinutes(1)));
        return b.tryConsume();
    }

    /** Test-only: drain stale buckets. */
    void resetAll() {
        senderBuckets.clear();
        endUserBuckets.clear();
    }

    /** Token bucket. Refills {@code refillTokens} per {@code refillPeriod}. */
    private static final class Bucket {
        private final long capacity;
        private final long refillTokens;
        private final Duration refillPeriod;
        private double tokens;
        private Instant lastRefill;

        Bucket(long capacity, long initialTokens, long refillTokens, Duration refillPeriod) {
            this.capacity = capacity;
            this.tokens = initialTokens;
            this.refillTokens = refillTokens;
            this.refillPeriod = refillPeriod;
            this.lastRefill = Instant.now();
        }

        synchronized boolean tryConsume() {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        private void refill() {
            Instant now = Instant.now();
            double elapsedNanos = Duration.between(lastRefill, now).toNanos();
            double rate = (double) refillTokens / refillPeriod.toNanos();
            double added = elapsedNanos * rate;
            if (added > 0) {
                tokens = Math.min(capacity, tokens + added);
                lastRefill = now;
            }
        }
    }
}
