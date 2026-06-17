package com.yayatechandinnovations.yayaagentic.core.entity;

import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.util.List;
import java.util.Optional;

/**
 * Session-scoped, engine-owned memory for tool-produced entities. Distinct
 * from {@link com.yayatechandinnovations.yayaagentic.memory.WorkingMemory}
 * — see {@code docs/design/entity-memory-design.md} §4 for the rationale.
 *
 * <p>Conflict policy is the caller's responsibility (the engine consults
 * the producing tool's {@code EntityCapture.onConflict}). This SPI just
 * stores, retrieves, and clears.</p>
 */
public interface EntityMemory {

    /**
     * Add or replace an entity for this session. {@link EntityKind#COLLECTION}
     * entries append to a list under {@code entity.name()}; {@link EntityKind#SINGLETON}
     * entries replace any existing value at the caller's discretion (the
     * conflict-policy check is upstream of this call).
     */
    void capture(Ids.SessionId session, EntityRef entity);

    /** Returns the most recently captured entity with this name, if any. */
    Optional<EntityRef> resolve(Ids.SessionId session, String name);

    /**
     * Snapshot of all entities for this session in capture order. Used by
     * {@code PromptBuilder} (visibility-filtered) and admin observability.
     */
    List<EntityRef> all(Ids.SessionId session);

    /** Remove a specific named entity. No-op if absent. */
    void clear(Ids.SessionId session, String name);

    /**
     * Remove all entities at the given scope. Called by the engine at
     * intent transitions ({@link EntityScope#INTENT}, phase 2), turn
     * boundaries ({@link EntityScope#TURN}, phase 2), and session end
     * ({@link EntityScope#SESSION}).
     */
    void clearScope(Ids.SessionId session, EntityScope scope);
}
