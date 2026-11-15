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
        boolean requiresApprovedQuote,
        /**
         * A tap on a bound action button (whatsapp-ops-integration-design.md
         * §6.4) may run this tool WITHOUT a confirm frame: the message that
         * carried the button already showed the full operation, so the tap is
         * the one consent (rule R1). Opt-in per tool — a sender can never make
         * an arbitrary tool one-tap. The call still runs the Authorizer chain
         * with the user's own credentials.
         */
        boolean preconfirmableByAction,
        /**
         * The tool may ask the user for a secret (a verification or payment
         * code) by returning {@code _secretPrompt} in its result. The engine
         * then holds the call, collects the value through a masked prompt and
         * runs the same call once more with {@code {secret.value}} set for that
         * request only. A {@code _secretPrompt} from a tool without this flag
         * is dropped. See {@code docs/guides/admin-panel-sessions.md} §15.
         */
        boolean secretInput,
        /**
         * The tool's confirmation may share one card with others: when one
         * model answer holds two or more confirmable calls of plannable tools,
         * the engine shows them as the steps of one plan the user agrees to
         * once, instead of one confirm frame. Meaningful only on a
         * {@code confirmable} tool; never set it on a {@code secretInput} one.
         * See {@code docs/guides/admin-panel-sessions.md} §16.
         */
        boolean plannable,
        /**
         * The tool may be called by a headless run — the profile's agent
         * answering a stored question with nobody present
         * ({@code docs/guides/admin-panel-sessions.md} §17). A headless run is
         * offered only the tools that carry this flag, and a call to any other
         * is refused. It is for reads meant for unattended use: the catalogue
         * refuses it on a tool that is not an HTTP {@code GET}, or that is
         * {@code confirmable}, {@code secretInput}, {@code clientInvocable},
         * {@code plannable} or {@code preconfirmableByAction}.
         */
        boolean headless
) {
    /** Back-compat: a tool no headless run may call. */
    public ToolPolicy(Duration timeout, int maxRetries, boolean idempotent,
                      boolean confirmable, boolean clientInvocable, String confirmPrompt,
                      boolean requiresBancaContext, boolean requiresApprovedQuote,
                      boolean preconfirmableByAction, boolean secretInput, boolean plannable) {
        this(timeout, maxRetries, idempotent, confirmable, clientInvocable, confirmPrompt,
                requiresBancaContext, requiresApprovedQuote, preconfirmableByAction, secretInput, plannable,
                false);
    }

    /** Back-compat: a tool that is always confirmed on a card of its own. */
    public ToolPolicy(Duration timeout, int maxRetries, boolean idempotent,
                      boolean confirmable, boolean clientInvocable, String confirmPrompt,
                      boolean requiresBancaContext, boolean requiresApprovedQuote,
                      boolean preconfirmableByAction, boolean secretInput) {
        this(timeout, maxRetries, idempotent, confirmable, clientInvocable, confirmPrompt,
                requiresBancaContext, requiresApprovedQuote, preconfirmableByAction, secretInput, false);
    }

    /** Back-compat: a tool that never asks for a secret. */
    public ToolPolicy(Duration timeout, int maxRetries, boolean idempotent,
                      boolean confirmable, boolean clientInvocable, String confirmPrompt,
                      boolean requiresBancaContext, boolean requiresApprovedQuote,
                      boolean preconfirmableByAction) {
        this(timeout, maxRetries, idempotent, confirmable, clientInvocable, confirmPrompt,
                requiresBancaContext, requiresApprovedQuote, preconfirmableByAction, false);
    }

    /** Back-compat: not runnable from a bound action without a confirm frame. */
    public ToolPolicy(Duration timeout, int maxRetries, boolean idempotent,
                      boolean confirmable, boolean clientInvocable, String confirmPrompt,
                      boolean requiresBancaContext, boolean requiresApprovedQuote) {
        this(timeout, maxRetries, idempotent, confirmable, clientInvocable, confirmPrompt,
                requiresBancaContext, requiresApprovedQuote, false);
    }

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
