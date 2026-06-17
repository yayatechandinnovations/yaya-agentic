package com.yayatechandinnovations.yayaagentic.tool;

/**
 * Policy for what happens when a {@link com.yayatechandinnovations.yayaagentic.core.entity.EntityKind#SINGLETON}
 * capture fires under a name that already exists in
 * {@link com.yayatechandinnovations.yayaagentic.core.entity.EntityMemory}.
 * See {@code docs/design/entity-memory-design.md} §7.
 */
public enum OnConflict {
    /**
     * v1 default. Pause the turn, emit a {@code UiHint("entity_conflict")},
     * let the LLM paraphrase the question and the user pick from
     * {@code continue_existing} / {@code replace} / {@code view_existing}.
     */
    ASK_USER,
    /** Silent replace. Operator-opt-in for genuinely transient entities. */
    OVERWRITE,
    /** Silent keep-existing. Operator-opt-in for idempotency. */
    IGNORE,
    /** Raise a typed failure that the LLM continuation paraphrases. */
    ERROR,
    /**
     * Valid only when the capture's
     * {@link com.yayatechandinnovations.yayaagentic.core.entity.EntityKind}
     * is {@code COLLECTION}; appends to the list under that name.
     */
    APPEND
}
