package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.engine.QuickReplies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The options last offered to a WhatsApp user (assistant-channels design
 * §2.6), kept 20 minutes per (tenant, sender, phone) so a tap or a typed
 * number maps back to what the person saw: {@code ListId} / button payload
 * {@code opt:<id>} → the option's {@code send}; a reply that is only a number
 * after a numbered-text list → that row's {@code send}; the «Más…» row → the
 * next page without waking the engine. A new outbound replaces the set; one
 * without options clears it, so a stale «2» is never read against an old list.
 */
@Component
public class OfferedOptionsStore {

    private static final Logger log = LoggerFactory.getLogger(OfferedOptionsStore.class);

    public static final Duration TTL = Duration.ofMinutes(20);
    private static final String KEY_PREFIX = "yaya:wa:offered:";

    /**
     * @param numbered the options went out as numbered text, so a bare number names one
     * @param offset   the first option of the page the user is looking at
     * @param payload  the canonical {@code quick_replies} payload, for the next page
     */
    public record Offered(String sessionId, List<QuickReplies.Option> options, boolean numbered, int offset,
                          Map<String, Object> payload) {
        public Offered {
            options = options == null ? List.of() : List.copyOf(options);
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }

        public Offered atOffset(int newOffset) {
            return new Offered(sessionId, options, numbered, newOffset, payload);
        }

        /** The rows of the page the user is looking at, «Más…» included when one follows. */
        public List<QuickReplies.Option> page() {
            if (offset < 0 || offset >= options.size()) return List.of();
            int remaining = options.size() - offset;
            int take = remaining <= DefaultInteractiveRenderer.FIRST_PAGE_ROWS + 1
                    ? remaining : DefaultInteractiveRenderer.FIRST_PAGE_ROWS;
            List<QuickReplies.Option> rows = new ArrayList<>(options.subList(offset, offset + take));
            if (take < remaining) rows.add(MORE);
            return rows;
        }

        public boolean hasMore() {
            return options.size() - offset > DefaultInteractiveRenderer.FIRST_PAGE_ROWS + 1;
        }
    }

    /** The paging row, as an option: tapping it or typing its number asks for the next page. */
    public static final QuickReplies.Option MORE =
            new QuickReplies.Option(DefaultInteractiveRenderer.MORE_ROW_ID, "Más…", "Más…");

    /** The key-value seam: Redis in production, a map in tests. */
    interface Kv {
        String get(String key);
        void put(String key, String value, Duration ttl);
        void delete(String key);
    }

    private final Kv kv;
    private final ObjectMapper json;

    @org.springframework.beans.factory.annotation.Autowired
    public OfferedOptionsStore(StringRedisTemplate redis, ObjectMapper json) {
        this(new Kv() {
            @Override public String get(String key) { return redis.opsForValue().get(key); }
            @Override public void put(String key, String value, Duration ttl) { redis.opsForValue().set(key, value, ttl); }
            @Override public void delete(String key) { redis.delete(key); }
        }, json);
    }

    OfferedOptionsStore(Kv kv, ObjectMapper json) {
        this.kv = kv;
        this.json = json;
    }

    /** An instance over a plain map, for tests and for a deployment without Redis. */
    public static OfferedOptionsStore inMemory(ObjectMapper json) {
        Map<String, String> map = new ConcurrentHashMap<>();
        return new OfferedOptionsStore(new Kv() {
            @Override public String get(String key) { return map.get(key); }
            @Override public void put(String key, String value, Duration ttl) { map.put(key, value); }
            @Override public void delete(String key) { map.remove(key); }
        }, json);
    }

    public void remember(Ids.TenantId tenant, SenderId sender, String phone, Offered offered) {
        if (offered == null || offered.options().isEmpty()) {
            forget(tenant, sender, phone);
            return;
        }
        try {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sessionId", offered.sessionId());
            row.put("numbered", offered.numbered());
            row.put("offset", offered.offset());
            row.put("payload", offered.payload());
            kv.put(key(tenant, sender, phone), json.writeValueAsString(row), TTL);
        } catch (Exception e) {
            log.debug("offered options not stored for {}: {}", phone, e.getMessage());
        }
    }

    public Optional<Offered> recall(Ids.TenantId tenant, SenderId sender, String phone) {
        try {
            String raw = kv.get(key(tenant, sender, phone));
            if (raw == null || raw.isBlank()) return Optional.empty();
            Map<String, Object> row = json.readValue(raw, new TypeReference<Map<String, Object>>() {});
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = row.get("payload") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : Map.of();
            List<QuickReplies.Option> options = QuickReplies.read(payload);
            if (options.isEmpty()) return Optional.empty();
            return Optional.of(new Offered(
                    row.get("sessionId") == null ? null : String.valueOf(row.get("sessionId")),
                    options,
                    Boolean.TRUE.equals(row.get("numbered")),
                    row.get("offset") instanceof Number n ? n.intValue() : 0,
                    payload));
        } catch (Exception e) {
            log.debug("offered options not read for {}: {}", phone, e.getMessage());
            return Optional.empty();
        }
    }

    public void forget(Ids.TenantId tenant, SenderId sender, String phone) {
        try {
            kv.delete(key(tenant, sender, phone));
        } catch (RuntimeException e) {
            log.debug("offered options not cleared for {}: {}", phone, e.getMessage());
        }
    }

    /** The option an id names (with or without the {@code opt:} prefix); the «Más…» row for its id. */
    public static Optional<QuickReplies.Option> byId(Offered offered, String id) {
        if (offered == null || id == null) return Optional.empty();
        String wanted = id.startsWith(DefaultInteractiveRenderer.OPTION_PREFIX)
                ? id.substring(DefaultInteractiveRenderer.OPTION_PREFIX.length()) : id;
        if (wanted.isBlank()) return Optional.empty();
        if (DefaultInteractiveRenderer.MORE_ROW_ID.equals(wanted)) return Optional.of(MORE);
        for (QuickReplies.Option o : offered.options()) if (o.id().equals(wanted)) return Optional.of(o);
        return Optional.empty();
    }

    /** The option a typed number names on the page the user is looking at (1-based). */
    public static Optional<QuickReplies.Option> byNumber(Offered offered, String text) {
        if (offered == null || !offered.numbered() || text == null) return Optional.empty();
        String t = text.strip().replaceAll("[.)]$", "");
        if (!t.matches("\\d{1,2}")) return Optional.empty();
        int n = Integer.parseInt(t);
        List<QuickReplies.Option> page = offered.page();
        if (n < 1 || n > page.size()) return Optional.empty();
        return Optional.of(page.get(n - 1));
    }

    private static String key(Ids.TenantId tenant, SenderId sender, String phone) {
        return KEY_PREFIX + (tenant == null ? "platform" : tenant.value()) + ":" + sender.value() + ":" + phone;
    }
}
