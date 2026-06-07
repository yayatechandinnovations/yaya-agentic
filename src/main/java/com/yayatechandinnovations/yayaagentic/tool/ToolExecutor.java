package com.yayatechandinnovations.yayaagentic.tool;

import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.core.Turn;

import java.util.List;
import java.util.Map;

/**
 * Single dispatch point for the engine. Validates args against the input
 * schema, then either dispatches (Bean / HTTP) and returns a result, or
 * tells the engine which required fields are missing so it can ask the
 * user a focused follow-up question instead of guessing.
 *
 * <p>The two-variant {@link Outcome} keeps the M2 elicitation guardrail
 * (design §6.4) out of the engine's hot path — the executor knows the
 * schema, so it owns the "is this dispatchable yet?" decision.</p>
 */
public interface ToolExecutor {

    /** Backwards-compat overload — the executor mints a fresh call id. */
    default Outcome execute(ToolDescriptor descriptor,
                            Map<String, Object> args,
                            ExecutionContext ctx) {
        return execute(descriptor, args, ctx, null);
    }

    /**
     * Dispatch with an optional caller-supplied call id. The LLM-driven
     * path passes the id Anthropic minted in its {@code tool_use} block so
     * the corresponding {@code tool_result} can be matched on continuation;
     * other callers pass {@code null} and let the executor mint one.
     */
    Outcome execute(ToolDescriptor descriptor,
                    Map<String, Object> args,
                    ExecutionContext ctx,
                    String suggestedCallId);

    sealed interface Outcome permits Outcome.Dispatched, Outcome.NeedsInput {

        /** Tool ran (or its dispatcher reported a recoverable failure). */
        record Dispatched(Turn.ToolResult result) implements Outcome {}

        /** Required arguments are missing — engine must ask the user. */
        record NeedsInput(List<String> missingFields,
                          List<String> otherViolations) implements Outcome {}
    }
}
