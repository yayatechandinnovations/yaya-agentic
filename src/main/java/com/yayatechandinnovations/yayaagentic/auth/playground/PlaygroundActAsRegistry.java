package com.yayatechandinnovations.yayaagentic.auth.playground;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store of the {@link ActAs} spec selected at session start, keyed
 * by session id. See {@code docs/design/playground-actas-auth-design.md} §5.3.
 *
 * <p>This is intentionally NOT persisted: the spec lives only as long as the
 * session is active in {@code DefaultConversationEngine.active}. Process
 * restart drops every entry — matching the lifetime of the in-memory session
 * map itself. Tokens never reach the recorder, the DB, or the log.
 *
 * <p>Used by {@code SessionController}:
 * <ul>
 *   <li>{@code start} — store the spec immediately after a successful start.</li>
 *   <li>{@code sendMessage} — look up the stored spec, re-materialize headers
 *       on every turn so the runtime sees the same credential as it did at
 *       start.</li>
 *   <li>{@code end} — clear the spec.</li>
 * </ul>
 */
@Component
public class PlaygroundActAsRegistry {

    private final Map<Ids.SessionId, ActAs> bySession = new ConcurrentHashMap<>();

    public void put(Ids.SessionId sessionId, ActAs actAs) {
        if (actAs == null) return;
        bySession.put(sessionId, actAs);
    }

    public Optional<ActAs> get(Ids.SessionId sessionId) {
        return Optional.ofNullable(bySession.get(sessionId));
    }

    public void remove(Ids.SessionId sessionId) {
        bySession.remove(sessionId);
    }

    /** Test-only — count of stored entries. Never call from production code. */
    int size() {
        return bySession.size();
    }
}
