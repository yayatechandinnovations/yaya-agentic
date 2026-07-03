package com.yayatechandinnovations.yayaagentic.startflow;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Where a {@link OutputCapture} writes the extracted value. Persisted as JSONB
 * with a {@code kind} discriminator. See
 * {@code docs/design/session-start-flows.md} §5.2 and §5.5.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = CaptureTarget.Entity.class, name = "Entity"),
        @JsonSubTypes.Type(value = CaptureTarget.StepVar.class, name = "StepVar"),
})
public sealed interface CaptureTarget permits CaptureTarget.Entity, CaptureTarget.StepVar {

    /**
     * Lands in {@code EntityMemory} (session-scoped), reusing the exact
     * semantics of {@code tool/EntityCapture}. {@code visibleToLlm} decides
     * whether the model may use it for the rest of the conversation (§5.5,
     * level 1) — <b>defaults ON</b>: most flows exist to feed the model.
     */
    record Entity(String name, boolean visibleToLlm) implements CaptureTarget {
        /** Convenience: share with the assistant (the default). */
        public Entity(String name) { this(name, true); }
    }

    /** Flow-private scratch (in the cursor), never placed in a prompt — right for secrets/OTPs. */
    record StepVar(String name) implements CaptureTarget {}
}
