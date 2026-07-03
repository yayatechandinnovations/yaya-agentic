package com.yayatechandinnovations.yayaagentic.startflow;

import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process {@link StartFlowStateStore} — a plain (non-bean) class for unit
 * tests. Production wiring is {@code PostgresStartFlowStateStore}.
 */
public class InMemoryStartFlowStateStore implements StartFlowStateStore {

    private final Map<String, StartFlowCursor> cursors = new ConcurrentHashMap<>();

    @Override
    public Optional<StartFlowCursor> load(Ids.SessionId session) {
        return session == null ? Optional.empty() : Optional.ofNullable(cursors.get(session.value()));
    }

    @Override
    public void save(StartFlowCursor cursor) {
        if (cursor != null) cursors.put(cursor.session().value(), cursor);
    }

    @Override
    public void delete(Ids.SessionId session) {
        if (session != null) cursors.remove(session.value());
    }
}
