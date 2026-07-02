package com.yayatechandinnovations.yayaagentic.tool;

import java.time.Duration;

/**
 * Per-tool execution policy. {@code confirmable} forces a two-phase
 * "preview → confirm" pattern for destructive actions (see design §16, q7).
 *
 * <p>{@code clientInvocable} is the tap-path analog of "out-of-profile tools are
 * never sent to the LLM": only a tool with {@code clientInvocable == true} can be
 * run through the client-initiated action channel ({@code POST /sessions/{id}/actions}).
 * Default {@code false} keeps every existing tool locked to the model path — a
 * raw {@code charge_card} must go through the LLM + confirm, never a direct tap.
 * See {@code ecommerce-chat-design.md} §9.</p>
 */
public record ToolPolicy(
        Duration timeout,
        int maxRetries,
        boolean idempotent,
        boolean confirmable,
        boolean clientInvocable
) {
    /** Back-compat: a model-path-only tool ({@code clientInvocable == false}). */
    public ToolPolicy(Duration timeout, int maxRetries, boolean idempotent, boolean confirmable) {
        this(timeout, maxRetries, idempotent, confirmable, false);
    }

    public static ToolPolicy defaults() {
        return new ToolPolicy(Duration.ofSeconds(10), 0, true, false, false);
    }
}
