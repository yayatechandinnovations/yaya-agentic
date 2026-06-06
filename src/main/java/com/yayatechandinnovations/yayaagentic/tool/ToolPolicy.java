package com.yayatechandinnovations.yayaagentic.tool;

import java.time.Duration;

/**
 * Per-tool execution policy. {@code confirmable} forces a two-phase
 * "preview → confirm" pattern for destructive actions (see design §16, q7).
 */
public record ToolPolicy(
        Duration timeout,
        int maxRetries,
        boolean idempotent,
        boolean confirmable
) {
    public static ToolPolicy defaults() {
        return new ToolPolicy(Duration.ofSeconds(10), 0, true, false);
    }
}
