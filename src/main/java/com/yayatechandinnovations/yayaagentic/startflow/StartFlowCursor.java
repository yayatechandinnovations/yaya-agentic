package com.yayatechandinnovations.yayaagentic.startflow;

import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.time.Instant;
import java.util.Map;

/**
 * Durable position of a paused interactive flow, so a multi-turn Elicit survives
 * across inbound messages (and process restarts). Only paused flows have a
 * cursor — it's written on pause and deleted on completion/abort. See
 * {@code docs/design/session-start-flows.md} §7.3.
 *
 * @param currentStepKey the paused {@link StartStep.Elicit} key awaiting a reply
 * @param scratch        flow-private step vars (may hold short-lived secrets)
 * @param stepOutputs    raw tool results keyed by step key, for {@code StepOutput} bindings
 */
public record StartFlowCursor(
        Ids.SessionId session,
        Ids.StartFlowId flow,
        String currentStepKey,
        Map<String, Object> scratch,
        Map<String, Object> stepOutputs,
        Instant updatedAt
) {
    public StartFlowCursor {
        scratch = scratch == null ? Map.of() : Map.copyOf(scratch);
        stepOutputs = stepOutputs == null ? Map.of() : Map.copyOf(stepOutputs);
    }
}
