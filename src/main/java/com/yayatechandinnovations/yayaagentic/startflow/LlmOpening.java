package com.yayatechandinnovations.yayaagentic.startflow;

import java.time.Duration;
import java.util.List;

/**
 * Configuration for an LLM-composed opening (§5.5, level 2). Carried on the
 * flow so its persisted shape is stable; the generation itself lands in SF-1.
 *
 * @param instruction     the only <b>trusted</b> directive ("Greet by first name, mention the latest order")
 * @param briefingEntities curated subset of shared entities handed to the model as the opening briefing
 * @param quickReplyHints  optional seed chips; final chips stay engine-emitted (non-negotiable #8)
 * @param stream           stream tokens (true) vs. one blocking short generation (false)
 * @param budget           hard cap on opening generation; overrun ⇒ fallback template
 */
public record LlmOpening(
        String instruction,
        List<String> briefingEntities,
        List<String> quickReplyHints,
        boolean stream,
        Duration budget
) {
    public LlmOpening {
        briefingEntities = briefingEntities == null ? List.of() : List.copyOf(briefingEntities);
        quickReplyHints = quickReplyHints == null ? List.of() : List.copyOf(quickReplyHints);
        if (budget == null) budget = Duration.ofSeconds(6);
    }
}
