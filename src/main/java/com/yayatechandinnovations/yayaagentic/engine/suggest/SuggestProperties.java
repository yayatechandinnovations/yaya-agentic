package com.yayatechandinnovations.yayaagentic.engine.suggest;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code yaya.agentic.suggest.*} — the reply suggester (assistant-channels
 * design §2.5). {@code enabled} defaults to true, {@code timeout} to 1.5 s;
 * {@code model} pins a (cheaper) model for the one structured call, else the
 * deployment's chat model answers.
 */
@ConfigurationProperties(prefix = "yaya.agentic.suggest")
public record SuggestProperties(Boolean enabled, String model, Duration timeout) {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofMillis(1500);

    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    public Duration effectiveTimeout() {
        return timeout == null || timeout.isZero() || timeout.isNegative() ? DEFAULT_TIMEOUT : timeout;
    }

    public String modelOrNull() {
        return model == null || model.isBlank() ? null : model.strip();
    }

    public static SuggestProperties defaults() {
        return new SuggestProperties(null, null, null);
    }
}
