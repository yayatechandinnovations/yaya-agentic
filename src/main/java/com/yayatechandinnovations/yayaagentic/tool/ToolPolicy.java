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
        boolean clientInvocable,
        /**
         * Operator-authored question shown when a confirmable tool pauses for
         * approval ("¿Deseas confirmar este boleto?"). In the tenant's own
         * language — it reaches the user verbatim. Null/blank → a neutral
         * "Do you want to continue?"; the tool id and raw args are NEVER
         * user-facing confirmation copy.
         */
        String confirmPrompt,
        /**
         * Network play-path (yaya-agentic-network-chat-runtime-design.md §2/§4):
         * when true, the engine REFUSES to dispatch this tool unless a banca
         * context is committed for the session, emitting a {@code banca_picker}
         * ui_hint instead. The tool never reads this — the engine gate does, in
         * {@code runToolDispatch}, before the confirmable gate. Read-class tools
         * and {@code banca_context_set} (which SETS the context) leave it false.
         */
        boolean requiresBancaContext,
        /**
         * Network play-path (design §4, {@code play_confirm}/{@code
         * prize_claim_network}): a HARD precondition enforced by the engine, not
         * the prompt — an approved confirm frame for THIS exact quote (callId
         * match, quote unchanged) must exist before dispatch. Idempotency key is
         * (sessionId, callId). Gate runs immediately before execute.
         */
        boolean requiresApprovedQuote
) {
    /** Back-compat: no network play-path gating. */
    public ToolPolicy(Duration timeout, int maxRetries, boolean idempotent,
                      boolean confirmable, boolean clientInvocable, String confirmPrompt) {
        this(timeout, maxRetries, idempotent, confirmable, clientInvocable, confirmPrompt, false, false);
    }

    /** Back-compat: no operator confirmation copy. */
    public ToolPolicy(Duration timeout, int maxRetries, boolean idempotent,
                      boolean confirmable, boolean clientInvocable) {
        this(timeout, maxRetries, idempotent, confirmable, clientInvocable, null);
    }

    /** Back-compat: a model-path-only tool ({@code clientInvocable == false}). */
    public ToolPolicy(Duration timeout, int maxRetries, boolean idempotent, boolean confirmable) {
        this(timeout, maxRetries, idempotent, confirmable, false, null);
    }

    public static ToolPolicy defaults() {
        return new ToolPolicy(Duration.ofSeconds(10), 0, true, false, false, null);
    }
}
