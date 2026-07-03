package com.yayatechandinnovations.yayaagentic.startflow;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Where a matched {@link Branch} sends control. Persisted as JSONB with a
 * {@code kind} discriminator. See {@code docs/design/session-start-flows.md} §5.3.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = BranchTarget.Step.class, name = "Step"),
        @JsonSubTypes.Type(value = BranchTarget.End.class, name = "End"),
        @JsonSubTypes.Type(value = BranchTarget.Abort.class, name = "Abort"),
})
public sealed interface BranchTarget permits BranchTarget.Step, BranchTarget.End, BranchTarget.Abort {

    /** Jump to another step by key. */
    record Step(String key) implements BranchTarget {}

    /** Finish the flow with an outcome label used to pick the greeting (e.g. {@code "success"}). */
    record End(String outcomeLabel) implements BranchTarget {}

    /** Stop with an audit reason; the user sees the fallback greeting. */
    record Abort(String reason) implements BranchTarget {}
}
