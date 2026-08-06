package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Channel-specific Micrometer metrics per design §17. Centralized so the
 * label conventions (tenant, sender_id, outcome) don't drift across the
 * webhook controller, the dispatch worker, and the outbound dispatcher.
 *
 * <p>Counter names match the design's metric table verbatim — operator
 * dashboards built against the design doc work without rename.
 */
@Component
public class ChannelMetrics {

    private final MeterRegistry registry;

    public ChannelMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /* ------------------------------ inbound ------------------------------ */

    public void inbound(String tenant, String senderId, String channelKind, String outcome) {
        Counter.builder("channel_inbound_total")
                .tags(Tags.of("tenant", safe(tenant),
                              "sender_id", safe(senderId),
                              "channel_kind", safe(channelKind),
                              "outcome", safe(outcome)))
                .register(registry)
                .increment();
    }

    public void signatureFailure(String tenant, String senderId) {
        Counter.builder("channel_inbound_signature_failures_total")
                .tags(Tags.of("tenant", safe(tenant), "sender_id", safe(senderId)))
                .register(registry)
                .increment();
    }

    /* ------------------------------ onboarding --------------------------- */

    /** Embedded-signup onboarding outcomes: started | completed | failed |
     *  conflict | subaccount_created | subaccount_reused. */
    public void embeddedSignup(String tenant, String outcome) {
        Counter.builder("channel_embedded_signup_total")
                .tags(Tags.of("tenant", safe(tenant), "outcome", safe(outcome)))
                .register(registry)
                .increment();
    }

    /* ------------------------------ dispatch ----------------------------- */

    public void dispatchLatency(String tenant, String senderId, String completionPolicy,
                                Duration elapsed) {
        Timer.builder("channel_dispatch_latency_seconds")
                .tags(Tags.of("tenant", safe(tenant),
                              "sender_id", safe(senderId),
                              "completion_policy", safe(completionPolicy)))
                .register(registry)
                .record(elapsed);
    }

    /* ------------------------------ outbound ----------------------------- */

    public void outbound(String tenant, String senderId, String kind, String result) {
        Counter.builder("channel_outbound_total")
                .tags(Tags.of("tenant", safe(tenant),
                              "sender_id", safe(senderId),
                              "kind", safe(kind),
                              "result", safe(result)))
                .register(registry)
                .increment();
    }

    public void serviceWindowBlocked(String tenant, String senderId) {
        Counter.builder("channel_outbound_service_window_blocked_total")
                .tags(Tags.of("tenant", safe(tenant), "sender_id", safe(senderId)))
                .register(registry)
                .increment();
    }

    public void optedOutBlocked(String tenant, String senderId) {
        Counter.builder("channel_outbound_opted_out_blocked_total")
                .tags(Tags.of("tenant", safe(tenant), "sender_id", safe(senderId)))
                .register(registry)
                .increment();
    }

    public void splitOutbound(String tenant, String senderId, int chunks) {
        Counter.builder("channel_outbound_split_total")
                .tags(Tags.of("tenant", safe(tenant),
                              "sender_id", safe(senderId)))
                .register(registry)
                .increment(chunks - 1);
    }

    /* ------------------------------ rate limits -------------------------- */

    public void rateLimited(String tenant, String senderId, String scope) {
        Counter.builder("channel_rate_limited_total")
                .tags(Tags.of("tenant", safe(tenant),
                              "sender_id", safe(senderId),
                              "scope", safe(scope)))
                .register(registry)
                .increment();
    }

    /* ------------------------------ transcription ------------------------ */

    public void transcription(String tenant, String senderId, String outcome) {
        Counter.builder("channel_transcription_total")
                .tags(Tags.of("tenant", safe(tenant),
                              "sender_id", safe(senderId),
                              "outcome", safe(outcome)))
                .register(registry)
                .increment();
    }

    /* ------------------------------ helpers ------------------------------ */

    private static String safe(String s) {
        return s == null ? "unknown" : s;
    }
}
