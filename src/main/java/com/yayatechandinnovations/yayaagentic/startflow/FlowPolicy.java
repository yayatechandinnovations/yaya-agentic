package com.yayatechandinnovations.yayaagentic.startflow;

import java.time.Duration;

/**
 * Whole-flow guardrails. See {@code docs/design/session-start-flows.md} §5.4, §9.5.
 *
 * @param totalBudget    hard wall for the synchronous start phase; overrun ⇒ fallback
 * @param maxSteps       loop/misconfig backstop
 * @param runScope       whether a reopened session re-runs the flow
 * @param allowWhenAnonymous must be true for pre-auth flows; gates the pre-auth lint (§9.2)
 */
public record FlowPolicy(
        Duration totalBudget,
        int maxSteps,
        RunScope runScope,
        boolean allowWhenAnonymous
) {
    public enum RunScope {
        /** Only a brand-new session runs the flow. The default. */
        FRESH_SESSION_ONLY,
        /** A reopened (idle-timeout) session re-runs the flow — e.g. to re-verify. */
        EVERY_REOPEN
    }

    public FlowPolicy {
        if (totalBudget == null) totalBudget = Duration.ofSeconds(4);
        if (maxSteps <= 0) maxSteps = 25;
        if (runScope == null) runScope = RunScope.FRESH_SESSION_ONLY;
    }

    public static FlowPolicy defaults() {
        return new FlowPolicy(Duration.ofSeconds(4), 25, RunScope.FRESH_SESSION_ONLY, true);
    }
}
