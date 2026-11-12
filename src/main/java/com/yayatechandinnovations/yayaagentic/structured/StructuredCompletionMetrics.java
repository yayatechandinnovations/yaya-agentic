package com.yayatechandinnovations.yayaagentic.structured;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Micrometer meters for {@code POST /v1/structured-completions}, tagged by
 * tenant and task so LLM spend can be attributed per integration.
 */
@Component
public class StructuredCompletionMetrics {

    private final MeterRegistry registry;

    public StructuredCompletionMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** One finished request — {@code outcome} is {@code ok} or the error code. */
    public void record(String tenant, String task, String outcome,
                       long inputTokens, long outputTokens, Duration elapsed) {
        Tags who = Tags.of("tenant", safe(tenant), "task", safe(task));
        Counter.builder("structured_completions_total")
                .tags(who.and("outcome", safe(outcome))).register(registry).increment();
        Timer.builder("structured_completion_duration_seconds")
                .tags(who.and("outcome", safe(outcome))).register(registry).record(elapsed);
        if (inputTokens > 0) {
            Counter.builder("structured_completion_input_tokens_total")
                    .tags(who).register(registry).increment(inputTokens);
        }
        if (outputTokens > 0) {
            Counter.builder("structured_completion_output_tokens_total")
                    .tags(who).register(registry).increment(outputTokens);
        }
    }

    private static String safe(String s) {
        return s == null || s.isBlank() ? "unknown" : s;
    }
}
