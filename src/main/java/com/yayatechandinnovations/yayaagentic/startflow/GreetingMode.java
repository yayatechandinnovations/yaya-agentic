package com.yayatechandinnovations.yayaagentic.startflow;

/**
 * Who writes the first message once the flow completes. See
 * {@code docs/design/session-start-flows.md} §5.5.
 */
public enum GreetingMode {
    /** Deterministic template render over the blackboard. No model call. SF-0. */
    STATIC_TEMPLATE,
    /** The model writes the opening from the briefing + operator instruction. SF-1. */
    LLM_COMPOSED,
    /** The template is the guaranteed scaffold/fallback; the model personalizes on top. SF-1. */
    HYBRID
}
