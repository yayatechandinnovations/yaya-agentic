package com.yayatechandinnovations.yayaagentic.startflow;

import java.util.List;
import java.util.Map;

/**
 * How the first message is written when the flow completes, keyed by the
 * {@link BranchTarget.End} outcome label. See
 * {@code docs/design/session-start-flows.md} §5.4, §5.5.
 *
 * @param outcomeGreetingTemplates outcome label → template ({@code "success"} → "Welcome back {customerName} 👋")
 * @param fallbackGreeting         used on abort / budget overrun / opening-gen failure / no template
 * @param llmOpening               null unless {@code mode} is LLM_COMPOSED/HYBRID
 */
public record GreetingPolicy(
        GreetingMode mode,
        Map<String, String> outcomeGreetingTemplates,
        String fallbackGreeting,
        List<String> quickReplyTemplates,
        LlmOpening llmOpening
) {
    public GreetingPolicy {
        if (mode == null) mode = GreetingMode.STATIC_TEMPLATE;
        outcomeGreetingTemplates = outcomeGreetingTemplates == null
                ? Map.of() : Map.copyOf(outcomeGreetingTemplates);
        quickReplyTemplates = quickReplyTemplates == null ? List.of() : List.copyOf(quickReplyTemplates);
    }

    /** A static-only policy with a single fallback greeting. */
    public static GreetingPolicy staticFallback(String fallbackGreeting) {
        return new GreetingPolicy(GreetingMode.STATIC_TEMPLATE, Map.of(), fallbackGreeting, List.of(), null);
    }
}
