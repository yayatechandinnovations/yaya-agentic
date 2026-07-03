package com.yayatechandinnovations.yayaagentic.startflow;

/**
 * One routing rule: when {@link Condition} holds, go to {@link BranchTarget}.
 * Evaluated in list order, first match wins. See
 * {@code docs/design/session-start-flows.md} §5.3.
 */
public record Branch(Condition when, BranchTarget target) {

    public Branch {
        if (when == null) throw new IllegalArgumentException("Branch.when must be set");
        if (target == null) throw new IllegalArgumentException("Branch.target must be set");
    }
}
