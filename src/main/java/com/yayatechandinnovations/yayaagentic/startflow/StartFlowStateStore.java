package com.yayatechandinnovations.yayaagentic.startflow;

import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.util.Optional;

/**
 * Durable storage for {@link StartFlowCursor}s (table {@code session_start_state}).
 * The engine never touches this directly — only the runner does (non-negotiable
 * #4). See {@code docs/design/session-start-flows.md} §7.3.
 */
public interface StartFlowStateStore {

    Optional<StartFlowCursor> load(Ids.SessionId session);

    void save(StartFlowCursor cursor);

    void delete(Ids.SessionId session);
}
