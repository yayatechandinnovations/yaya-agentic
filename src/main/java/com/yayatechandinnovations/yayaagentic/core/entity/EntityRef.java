package com.yayatechandinnovations.yayaagentic.core.entity;

import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.time.Instant;
import java.util.Map;

/**
 * A value captured from a tool output and carried forward as input to
 * subsequent tool calls. The framework uses {@code name} as the key the
 * LLM (or the engine, when invisible) references; {@code value} is held
 * server-side and never traverses the LLM's text channel.
 *
 * <p>Provenance fields exist so audit + replay can show which tool produced
 * which entity, and so admin observability can reconstruct the memory
 * state of a recorded session.</p>
 *
 * @param visibleToLlm carried on the ref itself (not just on the capture
 *                     declaration) because the same memory backing store is
 *                     consulted at prompt-projection time, which must
 *                     respect visibility regardless of which tool wrote it.
 */
public record EntityRef(
        String name,
        Object value,
        EntityScope scope,
        EntityKind kind,
        boolean visibleToLlm,
        Ids.ToolId producedBy,
        Instant capturedAt,
        Map<String, Object> metadata
) {

    public EntityRef {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("EntityRef.name must be non-blank");
        }
        if (scope == null) throw new IllegalArgumentException("EntityRef.scope must be set");
        if (kind == null) throw new IllegalArgumentException("EntityRef.kind must be set");
        if (capturedAt == null) capturedAt = Instant.now();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
