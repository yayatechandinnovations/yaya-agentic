package com.yayatechandinnovations.yayaagentic.api;

import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates the {@code clientContext} a client attaches to a message: which
 * screen and record the user has open. It reaches the model as unverified
 * per-turn context, so it is kept to three short, typed fields — anything
 * else is refused rather than passed along.
 */
final class ClientContexts {

    static final int MAX_SCREEN_ID = 96;
    static final int MAX_RECORD_REF = 160;
    static final int MAX_LABEL = 200;

    private static final Set<String> FIELDS = Set.of("screenId", "recordRef", "label");
    private static final Pattern SCREEN_ID = Pattern.compile("[A-Za-z0-9._:/\\-]+");
    private static final Pattern RECORD_REF = Pattern.compile("[\\p{L}\\p{N}._:/|=,#@+\\- ]+");

    private ClientContexts() {}

    /** The cleaned context, or {@code null} when the client sent none. */
    static Map<String, Object> validate(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) return null;
        for (String key : raw.keySet()) {
            if (!FIELDS.contains(key)) throw invalid("unknown field '" + clip(key) + "'");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        String screenId = text(raw, "screenId", MAX_SCREEN_ID);
        if (screenId != null) {
            if (!SCREEN_ID.matcher(screenId).matches()) {
                throw invalid("screenId may only contain letters, digits and . _ : / -");
            }
            out.put("screenId", screenId);
        }
        String recordRef = text(raw, "recordRef", MAX_RECORD_REF);
        if (recordRef != null) {
            if (!RECORD_REF.matcher(recordRef).matches()) {
                throw invalid("recordRef contains characters that are not allowed");
            }
            out.put("recordRef", recordRef);
        }
        String label = text(raw, "label", MAX_LABEL);
        if (label != null) out.put("label", label);
        return out.isEmpty() ? null : out;
    }

    private static String text(Map<String, Object> raw, String field, int max) {
        Object value = raw.get(field);
        if (value == null) return null;
        if (!(value instanceof String s)) throw invalid(field + " must be a string");
        // Line breaks and control characters could fake structure in the prompt.
        String cleaned = s.replaceAll("[\\p{Cntrl}\\u2028\\u2029]+", " ").strip();
        if (cleaned.isEmpty()) return null;
        if (cleaned.length() > max) throw invalid(field + " is longer than " + max + " characters");
        return cleaned;
    }

    private static String clip(String s) {
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    private static ChatApiException invalid(String detail) {
        return new ChatApiException(HttpStatus.BAD_REQUEST, "invalid_client_context",
                "clientContext: " + detail, 0);
    }
}
