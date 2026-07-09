package com.yayatechandinnovations.yayasales.integration.internal;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * §11.2 — the {@code _uiHint} frame. yaya-sales owns the kinds and payload
 * schemas (versioned with the storefront renderer); the agent engine passes
 * the frame through as a {@code TurnEvent.UiHint} and strips it from what the
 * LLM sees. Server-emitted, never model prose (#8).
 */
final class UiHints {

    private UiHints() {}

    /** Wrap a tool response body with a UI-hint frame. */
    static Map<String, Object> framed(Map<String, Object> body, String kind, Object payload) {
        Map<String, Object> out = new LinkedHashMap<>(body);
        out.put("_uiHint", Map.of("kind", kind, "payload", payload));
        return out;
    }
}
