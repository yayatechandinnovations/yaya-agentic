package com.yayatechandinnovations.yayaagentic.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The reserved {@code _askUser} tool-result key (assistant-channels design
 * §2.4): a tool that asks the user a question says what the answers are.
 *
 * <pre>
 * "_askUser": { "kind": "choice|yes_no", "question": "¿…?",
 *               "options": [ { "id": "all", "label": "Todas", "send": "Para todas las estaciones" } ] }
 * </pre>
 *
 * Handled like {@code _uiHint}: stripped before the model sees the result,
 * then emitted by the engine as {@code quick_replies {source:"ask"}} after the
 * assistant's final text of the turn. {@code yes_no} without options is the
 * localised «Sí» / «No». The model still writes the sentence; the chips come
 * from the server that knows the options (non-negotiable #8).
 */
final class AskUser {

    static final String KEY = "_askUser";

    private AskUser() {}

    /** The ask and the value without it; null when the value carries none. */
    record Split(Map<String, Object> ask, Object strippedValue) {}

    @SuppressWarnings("unchecked")
    static Split split(Object toolValue) {
        if (!(toolValue instanceof Map<?, ?> map) || !map.containsKey(KEY)) return null;
        Map<String, Object> stripped = new LinkedHashMap<>();
        map.forEach((k, v) -> {
            if (!KEY.equals(k)) stripped.put(String.valueOf(k), v);
        });
        Object ask = map.get(KEY);
        // A malformed ask is still stripped (the LLM never sees render plumbing).
        return new Split(ask instanceof Map<?, ?> m ? (Map<String, Object>) m : null, stripped);
    }

    /** The options to offer, or an empty list when the ask has nothing tappable. */
    static List<QuickReplies.Option> options(Map<String, Object> ask, String language) {
        if (ask == null) return List.of();
        String kind = ask.get("kind") == null ? "choice" : String.valueOf(ask.get("kind"));
        List<QuickReplies.Option> given = new ArrayList<>();
        if (ask.get("options") instanceof List<?>) {
            given.addAll(QuickReplies.read(Map.of("options", ask.get("options"))));
        }
        if ("yes_no".equals(kind) && given.isEmpty()) return QuickReplies.yesNo(language);
        return given;
    }
}
