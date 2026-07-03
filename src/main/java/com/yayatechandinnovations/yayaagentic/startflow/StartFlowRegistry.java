package com.yayatechandinnovations.yayaagentic.startflow;

import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.util.List;
import java.util.Optional;

/**
 * Versioned load/pin for {@link StartFlow}s — mirrors {@code ProfileRegistry}.
 * The engine resolves "latest" to a concrete version once at {@code start()}
 * and pins it for the session. See {@code docs/design/session-start-flows.md} §6.
 *
 * <p>SF-0 ships an in-memory implementation; a Postgres-backed registry
 * (table {@code session_start_flows}) lands with admin CRUD.</p>
 */
public interface StartFlowRegistry {

    Optional<StartFlow> find(Ids.TenantId tenant, Ids.StartFlowId id);

    Optional<StartFlow> findLatest(Ids.TenantId tenant, Ids.StartFlowId id);

    List<StartFlow> listFor(Ids.TenantId tenant);

    void register(StartFlow flow);
}
