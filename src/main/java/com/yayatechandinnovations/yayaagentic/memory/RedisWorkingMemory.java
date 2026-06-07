package com.yayatechandinnovations.yayaagentic.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Hash-per-session storage:
 * <pre>
 *   key = yaya:wm:{sessionId}
 *   field = slot key
 *   value = JSON-encoded slot value
 * </pre>
 * Every read and every write refreshes the per-key TTL to
 * {@code yaya.agentic.session.idle-timeout}.
 */
@Component
public class RedisWorkingMemory implements WorkingMemory {

    private static final TypeReference<Map<String, Object>> MAP_REF = new TypeReference<>() {};

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Duration ttl;

    public RedisWorkingMemory(StringRedisTemplate redis,
                              ObjectMapper json,
                              YayaAgenticProperties props) {
        this.redis = redis;
        this.json = json;
        this.ttl = props.session() == null || props.session().idleTimeout() == null
                ? Duration.ofMinutes(30)
                : props.session().idleTimeout();
    }

    @Override
    public void set(Ids.SessionId sessionId, Map<String, Object> values) {
        String key = key(sessionId);
        redis.delete(key);
        if (values != null && !values.isEmpty()) {
            redis.opsForHash().putAll(key, encodeAll(values));
        }
        redis.expire(key, ttl);
    }

    @Override
    public void merge(Ids.SessionId sessionId, Map<String, Object> values) {
        if (values == null || values.isEmpty()) return;
        String key = key(sessionId);
        redis.opsForHash().putAll(key, encodeAll(values));
        redis.expire(key, ttl);
    }

    @Override
    public Map<String, Object> get(Ids.SessionId sessionId) {
        String key = key(sessionId);
        Map<Object, Object> raw = redis.opsForHash().entries(key);
        if (raw.isEmpty()) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            try {
                out.put(k.toString(), json.readValue(v.toString(), Object.class));
            } catch (JsonProcessingException e) {
                out.put(k.toString(), v.toString());
            }
        });
        redis.expire(key, ttl);
        return out;
    }

    @Override
    public void clear(Ids.SessionId sessionId) {
        redis.delete(key(sessionId));
    }

    @Override
    public void remove(Ids.SessionId sessionId, String... keys) {
        if (keys == null || keys.length == 0) return;
        String key = key(sessionId);
        redis.opsForHash().delete(key, (Object[]) keys);
        redis.expire(key, ttl);
    }

    private Map<String, String> encodeAll(Map<String, Object> values) {
        Map<String, String> out = new LinkedHashMap<>();
        values.forEach((k, v) -> {
            try {
                out.put(k, json.writeValueAsString(v));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("WorkingMemory: failed to encode '" + k + "'", e);
            }
        });
        return out;
    }

    private static String key(Ids.SessionId sessionId) {
        return "yaya:wm:" + sessionId.value();
    }
}
