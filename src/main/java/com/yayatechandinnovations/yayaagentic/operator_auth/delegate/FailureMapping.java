package com.yayatechandinnovations.yayaagentic.operator_auth.delegate;

/**
 * Layer 4 — extracting an audit-only deny reason from the response body
 * when success criteria don't match. NEVER displayed to the user (the
 * UI shows a fixed generic message). See {@code docs/design/operator-auth-design.md} §5.4.
 */
public record FailureMapping(String reasonPath) {
    public static FailureMapping defaults() {
        return new FailureMapping(null);
    }
}
