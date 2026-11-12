package com.yayatechandinnovations.yayaagentic.structured;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Knobs for {@code POST /v1/structured-completions}.
 *
 * <ul>
 *   <li>{@code perMinute} — calls one tenant may make per minute (default 30).</li>
 *   <li>{@code timeout} — deadline for one request, schema-violation retry
 *       included (default 60 s); past it the caller gets 502.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "yaya.agentic.structured-completions")
public record StructuredCompletionsProperties(Integer perMinute, Duration timeout) {

    public int perMinuteOrDefault() {
        return perMinute == null || perMinute <= 0 ? 30 : perMinute;
    }

    public Duration timeoutOrDefault() {
        return timeout == null || timeout.isZero() || timeout.isNegative() ? Duration.ofSeconds(60) : timeout;
    }
}
