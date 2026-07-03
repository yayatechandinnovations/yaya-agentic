package com.yayatechandinnovations.yayaagentic.startflow;

/**
 * What the runner does when a {@link StartStep.RunTool} step fails (tool
 * FAILED, AuthZ DENIED, or a REQUIRED binding missing). See
 * {@code docs/design/session-start-flows.md} §5.4, §13.
 */
public record StepErrorPolicy(OnError onError, int maxRetries) {

    public enum OnError {
        /** Stop the flow → fallback greeting, hand off to the LLM. The safe default. */
        ABORT,
        /** Ignore the failure and continue to the next step. */
        CONTINUE,
        /** Re-invoke up to {@code maxRetries} times before applying ABORT. */
        RETRY
    }

    public StepErrorPolicy {
        if (onError == null) onError = OnError.ABORT;
        if (maxRetries < 0) maxRetries = 0;
    }

    public static StepErrorPolicy abort() { return new StepErrorPolicy(OnError.ABORT, 0); }
    public static StepErrorPolicy cont() { return new StepErrorPolicy(OnError.CONTINUE, 0); }
}
