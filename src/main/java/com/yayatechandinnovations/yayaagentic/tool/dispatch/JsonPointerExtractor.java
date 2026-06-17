package com.yayatechandinnovations.yayaagentic.tool.dispatch;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.NullNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RFC-6901 extraction for {@link com.yayatechandinnovations.yayaagentic.tool.EntityCapture}
 * — pulls a value out of a tool's structured output without raising on
 * missing paths. Missing or null nodes return {@code null} (capture is a
 * no-op rather than a failed dispatch).
 */
public final class JsonPointerExtractor {

    private final ObjectMapper mapper;

    public JsonPointerExtractor(ObjectMapper mapper) {
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
    }

    /**
     * Extract {@code pointer} from {@code source}. {@code source} may be a
     * {@code Map}, {@code List}, {@code JsonNode}, {@code String} (parsed
     * as JSON), or any POJO Jackson can serialize. Returns {@code null}
     * when the path is absent, the value is JSON null, or the source is
     * not navigable.
     */
    public Object extract(Object source, String pointer) {
        if (source == null || pointer == null || pointer.isBlank()) return null;
        JsonNode root = toNode(source);
        if (root == null || root.isMissingNode()) return null;

        JsonPointer ptr;
        try {
            ptr = JsonPointer.compile(pointer);
        } catch (IllegalArgumentException ex) {
            // Bad pointer is an operator-config bug at descriptor build
            // time, but we still degrade gracefully at runtime — capture
            // becomes a no-op rather than failing a user-visible turn.
            return null;
        }
        JsonNode at = root.at(ptr);
        if (at == null || at.isMissingNode() || at instanceof MissingNode || at instanceof NullNode) {
            return null;
        }
        return unwrap(at);
    }

    private JsonNode toNode(Object source) {
        if (source instanceof JsonNode jn) return jn;
        if (source instanceof CharSequence cs) {
            try {
                return mapper.readTree(cs.toString());
            } catch (Exception ex) {
                return null;
            }
        }
        try {
            return mapper.valueToTree(source);
        } catch (Exception ex) {
            return null;
        }
    }

    private Object unwrap(JsonNode node) {
        if (node.isTextual()) return node.textValue();
        if (node.isInt()) return node.intValue();
        if (node.isLong()) return node.longValue();
        if (node.isDouble() || node.isFloat()) return node.doubleValue();
        if (node.isBoolean()) return node.booleanValue();
        if (node.isArray()) {
            List<Object> out = new java.util.ArrayList<>(node.size());
            node.forEach(child -> out.add(unwrap(child)));
            return List.copyOf(out);
        }
        if (node.isObject()) {
            Map<String, Object> out = new LinkedHashMap<>();
            node.fields().forEachRemaining(e -> out.put(e.getKey(), unwrap(e.getValue())));
            return Map.copyOf(out);
        }
        // BigInteger / BigDecimal / binary — pass through as text rather
        // than guessing a Java numeric type that may overflow.
        return node.asText();
    }
}
