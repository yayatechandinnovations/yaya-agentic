package com.yayatechandinnovations.yayaagentic.tool;

/**
 * How aggressively an {@link EntityBinding} should fill a tool's input
 * arg from {@link com.yayatechandinnovations.yayaagentic.core.entity.EntityMemory}.
 * See {@code docs/design/entity-memory-design.md} §6.
 */
public enum BindingMode {
    /** Fill only when the LLM did not supply this arg. The common case. */
    FILL_IF_MISSING,
    /**
     * Memory wins regardless of what the LLM sent. Use for security-sensitive
     * args the LLM must never forge (auth tokens, principal subjects, the
     * primary entity that anchors an in-progress draft).
     */
    OVERRIDE,
    /**
     * Like {@link #FILL_IF_MISSING}, but if memory has no value the engine
     * routes through the existing elicitation flow instead of letting the
     * dispatch reach validation with a missing required field.
     */
    REQUIRED
}
