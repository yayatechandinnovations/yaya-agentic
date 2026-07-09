package com.yayatechandinnovations.yayaagentic.testsupport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.memory.WorkingMemory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory {@link WorkingMemory} that JSON-encodes/decodes each slot exactly
 * like {@code RedisWorkingMemory}, so tests exercise the real serialization
 * boundary (a slot read back is a decoded {@code Map}/{@code List}, not the
 * original object).
 */
public final class JsonWorkingMemory implements WorkingMemory {

    private final Map<String, Map<String, String>> store = new LinkedHashMap<>();
    private final ObjectMapper json;

    public JsonWorkingMemory(ObjectMapper json) {
        this.json = json;
    }

    @Override public void set(Ids.SessionId s, Map<String, Object> values) {
        store.put(s.value(), encode(values));
    }

    @Override public void merge(Ids.SessionId s, Map<String, Object> values) {
        store.computeIfAbsent(s.value(), k -> new LinkedHashMap<>()).putAll(encode(values));
    }

    @Override public Map<String, Object> get(Ids.SessionId s) {
        Map<String, String> raw = store.getOrDefault(s.value(), Map.of());
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            try { out.put(k, json.readValue(v, Object.class)); }
            catch (JsonProcessingException e) { throw new IllegalStateException(e); }
        });
        return out;
    }

    @Override public void clear(Ids.SessionId s) { store.remove(s.value()); }

    @Override public void remove(Ids.SessionId s, String... keys) {
        Map<String, String> bag = store.get(s.value());
        if (bag != null) for (String k : keys) bag.remove(k);
    }

    private Map<String, String> encode(Map<String, Object> values) {
        Map<String, String> out = new LinkedHashMap<>();
        values.forEach((k, v) -> {
            try { out.put(k, json.writeValueAsString(v)); }
            catch (JsonProcessingException e) { throw new IllegalStateException(e); }
        });
        return out;
    }
}
