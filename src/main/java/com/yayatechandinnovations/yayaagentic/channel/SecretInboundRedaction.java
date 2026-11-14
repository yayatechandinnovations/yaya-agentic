package com.yayatechandinnovations.yayaagentic.channel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.persistence.InboundMessageEntity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An inbound WhatsApp or SMS message that answers a secret prompt
 * ({@code docs/guides/admin-panel-sessions.md} §15) is a verification or
 * payment code. Once the worker knows that, the stored row keeps no copy of
 * it: neither the provider payload nor the display body.
 */
public final class SecretInboundRedaction {

    /** What every inbound store holds in place of the text. */
    public static final String MARKER = "(código ingresado)";

    private static final TypeReference<LinkedHashMap<String, Object>> RAW = new TypeReference<>() {};

    private SecretInboundRedaction() {}

    /**
     * True when {@code text}, arriving for the conversation open on
     * {@code session}, must not be kept: a code prompt is pending there (or
     * lapsed a moment ago) and the text is, or may hold, its code.
     */
    public static boolean holdsCode(com.yayatechandinnovations.yayaagentic.engine.ConversationEngine engine,
                                    java.util.Optional<com.yayatechandinnovations.yayaagentic.core.Ids.SessionId> session,
                                    String text, String channel) {
        if (session.isEmpty() || text == null || text.isBlank()) return false;
        return engine.awaitsSecret(session.get(), text, channel);
    }

    /** Replaces the message text in {@code row}; the caller saves it. */
    public static void apply(InboundMessageEntity row, ObjectMapper json) {
        row.setBodyRedacted(MARKER);
        row.setMediaTranscriptsJson(null);
        String redacted;
        try {
            Map<String, Object> raw = json.readValue(row.getRawPayloadJson(), RAW);
            raw.put("Body", MARKER);
            redacted = json.writeValueAsString(raw);
        } catch (Exception e) {
            // A payload that cannot be rewritten is not kept at all.
            redacted = "{\"Body\":\"" + MARKER + "\"}";
        }
        row.setRawPayloadJson(redacted);
    }
}
