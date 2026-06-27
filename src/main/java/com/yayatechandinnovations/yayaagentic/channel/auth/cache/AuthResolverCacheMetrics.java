package com.yayatechandinnovations.yayaagentic.channel.auth.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

/**
 * Micrometer counters for {@link AuthResolverTokenCache}. Label conventions
 * mirror {@code ChannelMetrics} so operators can build a single dashboard
 * across both channel and auth-resolver paths.
 *
 * <p>See {@code docs/design/channel-auth-resolver-design.md} §12 (audit) and
 * §15 (PR 4 cache instrumentation).
 */
@Component
public class AuthResolverCacheMetrics {

    private final MeterRegistry registry;

    public AuthResolverCacheMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void hit(String tenant) { count("hit", tenant); }
    public void miss(String tenant) { count("miss", tenant); }
    public void refresh(String tenant) { count("refresh", tenant); }
    public void invalidate(String tenant) { count("invalidate", tenant); }
    public void redisUnavailable(String tenant) { count("redis_unavailable", tenant); }
    public void expiredOnRead(String tenant) { count("expired_on_read", tenant); }

    private void count(String outcome, String tenant) {
        Counter.builder("auth_resolver_cache_total")
                .tags(Tags.of("tenant", safe(tenant), "outcome", outcome))
                .register(registry)
                .increment();
    }

    private static String safe(String s) {
        return s == null || s.isBlank() ? "unknown" : s;
    }
}
