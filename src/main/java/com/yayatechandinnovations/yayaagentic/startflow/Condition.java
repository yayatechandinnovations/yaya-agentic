package com.yayatechandinnovations.yayaagentic.startflow;

/**
 * A deliberately tiny predicate: {@code left OP right-literal}. Compound logic
 * is expressed as an ordered list of {@link Branch}es (first match wins), not
 * nested boolean algebra — this keeps the builder legible and the runner
 * trivial. See {@code docs/design/session-start-flows.md} §5.3.
 */
public record Condition(ValueSource left, Op op, Object right) {

    public enum Op { EQ, NEQ, EXISTS, ABSENT, GT, LT, CONTAINS, TRUTHY }

    public Condition {
        if (left == null) throw new IllegalArgumentException("Condition.left must be set");
        if (op == null) throw new IllegalArgumentException("Condition.op must be set");
    }

    /** A condition that always matches — used for a default/else branch. */
    public static Condition always() {
        return new Condition(new ValueSource.Literal(Boolean.TRUE), Op.TRUTHY, null);
    }
}
