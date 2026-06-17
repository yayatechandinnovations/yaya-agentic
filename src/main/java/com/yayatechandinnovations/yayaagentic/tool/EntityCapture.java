package com.yayatechandinnovations.yayaagentic.tool;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.entity.EntityKind;
import com.yayatechandinnovations.yayaagentic.core.entity.EntityScope;

import java.util.Optional;

/**
 * Operator-declared instruction to extract a value from a tool's output and
 * store it in {@link com.yayatechandinnovations.yayaagentic.core.entity.EntityMemory}.
 * See {@code docs/design/entity-memory-design.md} §5 and §7.
 *
 * @param name          memory key
 * @param jsonPointer   RFC-6901 pointer into the tool's output JSON
 * @param scope         lifetime (v1: {@link EntityScope#SESSION} only)
 * @param kind          singleton or collection
 * @param onConflict    policy when a SINGLETON value already exists under {@code name}
 * @param visibleToLlm  when {@code false}, the name is NEVER projected to the prompt
 *                      and the LLM cannot reference it (still bound silently)
 * @param rollbackTool  for {@link OnConflict#ASK_USER} + REPLACE: tool to dispatch
 *                      against the existing value before overwriting memory
 * @param describeTool  for {@link OnConflict#ASK_USER} + VIEW_EXISTING: read-only
 *                      tool to dispatch against the existing value
 */
public record EntityCapture(
        String name,
        String jsonPointer,
        EntityScope scope,
        EntityKind kind,
        OnConflict onConflict,
        boolean visibleToLlm,
        Optional<Ids.ToolId> rollbackTool,
        Optional<Ids.ToolId> describeTool
) {

    public EntityCapture {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("EntityCapture.name must be non-blank");
        }
        if (jsonPointer == null || jsonPointer.isBlank()) {
            throw new IllegalArgumentException("EntityCapture.jsonPointer must be non-blank");
        }
        // RFC 6901: an empty string is the whole document; otherwise must
        // start with '/'. We disallow empty here because capturing the whole
        // tool output as one entity is never what the operator wants.
        if (!jsonPointer.startsWith("/")) {
            throw new IllegalArgumentException(
                    "EntityCapture.jsonPointer must be RFC-6901 (start with '/'), got: " + jsonPointer);
        }
        if (scope == null) scope = EntityScope.SESSION;
        if (kind == null) kind = EntityKind.SINGLETON;
        if (onConflict == null) onConflict = OnConflict.ASK_USER;
        if (kind == EntityKind.COLLECTION && onConflict != OnConflict.APPEND) {
            // A collection capture without APPEND is almost certainly an
            // operator mistake — fail loud at descriptor-build time rather
            // than silently doing something surprising at dispatch time.
            throw new IllegalArgumentException(
                    "EntityCapture.kind=COLLECTION requires onConflict=APPEND, got: " + onConflict);
        }
        if (kind == EntityKind.SINGLETON && onConflict == OnConflict.APPEND) {
            throw new IllegalArgumentException(
                    "EntityCapture.onConflict=APPEND requires kind=COLLECTION, got SINGLETON");
        }
        rollbackTool = rollbackTool == null ? Optional.empty() : rollbackTool;
        describeTool = describeTool == null ? Optional.empty() : describeTool;
    }

    /** Convenience: a visible session-scoped singleton capture with ASK_USER. */
    public static EntityCapture singleton(String name, String jsonPointer) {
        return new EntityCapture(name, jsonPointer,
                EntityScope.SESSION, EntityKind.SINGLETON, OnConflict.ASK_USER,
                /* visibleToLlm */ true, Optional.empty(), Optional.empty());
    }
}
