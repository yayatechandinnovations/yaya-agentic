package com.yayatechandinnovations.yayaagentic.startflow;

import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.core.Session;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Runs a {@link StartFlow} deterministically at session start. The runner is
 * side-effect-only over {@code EntityMemory} + the tool plane; it never calls
 * the LLM (the engine owns opening generation). See
 * {@code docs/design/session-start-flows.md} §7.
 *
 * <p>SF-0 supports the synchronous, non-interactive path (RunTool + Decision +
 * branches) and a {@code STATIC_TEMPLATE} greeting. {@code Elicit} (multi-turn)
 * and LLM-composed openings land in SF-1/SF-2.</p>
 */
public interface StartFlowRunner {

    enum Status { COMPLETED, ABORTED, AWAITING_INPUT }

    /**
     * @param outcomeLabel  the {@link BranchTarget.End} label (COMPLETED) or {@code "fallback"} (ABORTED)
     * @param greeting      the rendered STATIC template — the opening on STATIC_TEMPLATE, and the
     *                      fallback if an LLM-composed opening fails; {@code null} keeps the profile greeting
     * @param quickReplies  rendered quick-reply chips (possibly empty)
     * @param openingPlan   non-null when the completed outcome wants an LLM-composed opening (§5.5);
     *                      the runner assembles the briefing but the ENGINE owns generation
     * @param pending       non-null when {@code status == AWAITING_INPUT}: the question to ask the user
     */
    record Result(Status status, String outcomeLabel, String greeting,
                  List<String> quickReplies, OpeningPlan openingPlan, Elicitation pending) {
        public Result {
            quickReplies = quickReplies == null ? List.of() : List.copyOf(quickReplies);
        }

        /** Back-compat: a purely static (template) result. */
        public Result(Status status, String outcomeLabel, String greeting, List<String> quickReplies) {
            this(status, outcomeLabel, greeting, quickReplies, null, null);
        }

        public Result(Status status, String outcomeLabel, String greeting,
                      List<String> quickReplies, OpeningPlan openingPlan) {
            this(status, outcomeLabel, greeting, quickReplies, openingPlan, null);
        }
    }

    /** A rendered question posed to the user while the flow is paused. */
    record Elicitation(String promptText, List<String> quickReplies,
                       String uiHintKind, Map<String, Object> uiHintPayload) {
        public Elicitation {
            quickReplies = quickReplies == null ? List.of() : List.copyOf(quickReplies);
            uiHintPayload = uiHintPayload == null ? Map.of() : Map.copyOf(uiHintPayload);
        }
    }

    /**
     * Everything the engine needs to generate an LLM-composed opening — assembled
     * by the runner (deterministic), consumed by the engine (which owns the model
     * call: tools disabled, capped, briefing wrapped as data). See §5.5, §7.
     *
     * @param briefing       curated {name → value} of SHARED entities only (visibleToLlm)
     * @param scaffold       rendered template for HYBRID mode (a draft to refine); null for LLM_COMPOSED
     */
    record OpeningPlan(String instruction, Map<String, Object> briefing,
                       List<String> quickReplyHints, String scaffold, Duration budget) {}

    /**
     * Run the flow for a freshly-started session until it completes, aborts, or
     * pauses on an {@code Elicit} (AWAITING_INPUT — a cursor is persisted).
     *
     * @param channelFacts channel-captured facts ({@code endUserPhone}, {@code channel.locale}, …),
     *                     typically the start request's hints
     */
    Result begin(Session session, StartFlow flow, ExecutionContext exec, Map<String, Object> channelFacts);

    /**
     * If an interactive flow is paused for this session awaiting a reply, apply
     * {@code replyText} and continue until the next pause / completion / abort.
     * Returns empty when no flow is paused (the caller runs the LLM as usual).
     */
    java.util.Optional<Result> resumeIfActive(Session session, ExecutionContext exec, String replyText);
}
