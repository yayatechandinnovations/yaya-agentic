package com.yayatechandinnovations.yayaagentic.engine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The one generic remote-rendering convenience (yaya-sales design §11.2): a
 * trusted tool response may carry a top-level {@code "_uiHint": {kind,
 * payload}} frame. The engine surfaces it as an engine-emitted
 * {@code TurnEvent.UiHint} and strips it from the value shown to the LLM —
 * non-negotiable #8 holds because the frame is server-emitted, never model
 * prose. Domain-neutral: any HTTP or Bean tool can render this way.
 */
final class UiHintPassThrough {

    static final String KEY = "_uiHint";

    private UiHintPassThrough() {}

    record Split(String kind, Map<String, Object> payload, Object strippedValue) {}

    /** Null when the value carries no (well-formed) frame and nothing to strip. */
    @SuppressWarnings("unchecked")
    static Split split(Object toolValue) {
        if (!(toolValue instanceof Map<?, ?> map) || !map.containsKey(KEY)) {
            return null;
        }
        Map<String, Object> stripped = new LinkedHashMap<>();
        map.forEach((k, v) -> {
            if (!KEY.equals(k)) stripped.put(String.valueOf(k), v);
        });

        Object frame = map.get(KEY);
        if (frame instanceof Map<?, ?> f
                && f.get("kind") != null && f.get("payload") instanceof Map<?, ?> payload) {
            return new Split(String.valueOf(f.get("kind")),
                    (Map<String, Object>) payload, stripped);
        }
        // Malformed frame: still strip it (the LLM never sees render plumbing),
        // but there is no hint to emit.
        return new Split(null, null, stripped);
    }
}
