package com.yayatechandinnovations.yayaagentic.engine;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.memory.WorkingMemory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A generic, working-memory-backed queue of {@code UiHint}s a tool wants the
 * engine to stream. A Bean tool handler cannot emit onto the SSE stream itself
 * (it returns a value), so it {@link #enqueue} render payloads here; the engine
 * {@link #drain}s them right after the tool result and streams them as
 * {@code TurnEvent.UiHint}s — the same discipline as {@code quick_replies}
 * (non-negotiable #8). The kinds and payloads are opaque to the engine: an app's
 * tools use this to paint their own rich chrome without leaking data into the
 * model's text output.
 */
@Component
public class PendingUiHints {

    static final String SLOT = "pendingUiHints";

    private final WorkingMemory memory;

    public PendingUiHints(WorkingMemory memory) {
        this.memory = memory;
    }

    /** Append one hint for the engine to flush after the current tool result. */
    @SuppressWarnings("unchecked")
    public void enqueue(Ids.SessionId session, String kind, Map<String, Object> payload) {
        Object raw = memory.get(session).get(SLOT);
        List<Object> queue = raw instanceof List<?> list ? new ArrayList<>((List<Object>) list) : new ArrayList<>();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("kind", kind);
        entry.put("payload", payload);
        queue.add(entry);
        memory.merge(session, Map.of(SLOT, queue));
    }

    /** Return and clear every queued hint. Empty when none were enqueued. */
    @SuppressWarnings("unchecked")
    public List<TurnEvent.UiHint> drain(Ids.SessionId session) {
        Object raw = memory.get(session).get(SLOT);
        if (!(raw instanceof List<?> list) || list.isEmpty()) return List.of();
        List<TurnEvent.UiHint> hints = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                Object kind = m.get("kind");
                Object payload = m.get("payload");
                if (kind != null && payload instanceof Map<?, ?> p) {
                    hints.add(new TurnEvent.UiHint(String.valueOf(kind), (Map<String, Object>) p));
                }
            }
        }
        memory.remove(session, SLOT);
        return hints;
    }
}
