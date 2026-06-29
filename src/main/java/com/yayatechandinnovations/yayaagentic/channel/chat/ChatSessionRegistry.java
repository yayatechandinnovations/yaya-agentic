package com.yayatechandinnovations.yayaagentic.channel.chat;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Binds a live chat session to the context the public front door needs to rebuild
 * an {@code AuthContext} on each subsequent message — without trusting client-
 * supplied identity per call ({@code chat-channel-design.md} §9). For anonymous
 * sessions the stored {@code deviceId} re-mints the same subject; for
 * authenticated sessions the client must re-present its bearer (re-verified by
 * the chain), so only non-secret routing context lives here.
 *
 * <p>Redis-backed with a TTL matched to the entry point's idle timeout, mirroring
 * {@code RedisWorkingMemory}.
 */
@Component
public class ChatSessionRegistry {

    private static final String KEY_PREFIX = "yaya:chat:sess:";

    private final StringRedisTemplate redis;

    public ChatSessionRegistry(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void register(Ids.SessionId session, Context ctx, Duration ttl) {
        String key = KEY_PREFIX + session.value();
        redis.opsForHash().putAll(key, Map.of(
                "tenant", ctx.tenant(),
                "entryPointId", ctx.entryPointId(),
                "handle", ctx.handle(),
                "deviceId", ctx.deviceId() == null ? "" : ctx.deviceId(),
                "authMode", ctx.authMode(),
                "authenticated", ctx.authenticated() ? "1" : "0"));
        redis.expire(key, ttl == null ? Duration.ofHours(24) : ttl);
    }

    /** Flip a session to authenticated after a successful AUTH_OPTIONAL upgrade
     *  (§8.4) — subsequent messages then run as the verified principal. */
    public void markAuthenticated(Ids.SessionId session) {
        redis.opsForHash().put(KEY_PREFIX + session.value(), "authenticated", "1");
    }

    public Optional<Context> get(Ids.SessionId session) {
        String key = KEY_PREFIX + session.value();
        Map<Object, Object> raw = redis.opsForHash().entries(key);
        if (raw == null || raw.isEmpty()) return Optional.empty();
        return Optional.of(new Context(
                str(raw, "tenant"),
                str(raw, "entryPointId"),
                str(raw, "handle"),
                emptyToNull(str(raw, "deviceId")),
                str(raw, "authMode"),
                "1".equals(str(raw, "authenticated"))));
    }

    public void touch(Ids.SessionId session, Duration ttl) {
        redis.expire(KEY_PREFIX + session.value(), ttl == null ? Duration.ofHours(24) : ttl);
    }

    public void remove(Ids.SessionId session) {
        redis.delete(KEY_PREFIX + session.value());
    }

    private static String str(Map<Object, Object> raw, String field) {
        Object v = raw.get(field);
        return v == null ? null : v.toString();
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    public record Context(String tenant, String entryPointId, String handle,
                          String deviceId, String authMode, boolean authenticated) {}
}
