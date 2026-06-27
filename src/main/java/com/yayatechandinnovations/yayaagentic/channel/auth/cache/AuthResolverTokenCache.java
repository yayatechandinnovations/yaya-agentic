package com.yayatechandinnovations.yayaagentic.channel.auth.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.yayatechandinnovations.yayaagentic.auth.secrets.JwtSecretCipher;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Redis-backed cache of resolver-minted JWTs, keyed on
 * {@code (tenantId, senderId, sha256(callerPhone))}. Values are
 * AES-256-GCM-encrypted via {@link JwtSecretCipher} so a Redis snapshot
 * does not expose a JWT. TTLs are bounded by the JWT's own {@code exp}
 * minus the resolver's {@code refresh_window_seconds}, so a key still
 * present in Redis is — by construction — at least one refresh-window away
 * from JWT expiry (modulo clock skew, defended against by the read-time
 * exp recheck).
 *
 * <p>See {@code docs/design/channel-auth-resolver-design.md} §8.
 */
@Component
public class AuthResolverTokenCache {

    private static final Logger log = LoggerFactory.getLogger(AuthResolverTokenCache.class);
    private static final String KEY_PREFIX = "auth-resolver-token:";

    private final StringRedisTemplate redis;
    private final JwtSecretCipher cipher;
    private final ObjectMapper json;
    private final Clock clock;
    private final AuthResolverCacheMetrics metrics;
    private final boolean enabled;

    public AuthResolverTokenCache(
            StringRedisTemplate redis,
            JwtSecretCipher cipher,
            ObjectMapper json,
            Clock clock,
            AuthResolverCacheMetrics metrics,
            @Value("${yaya.agentic.auth-resolver.cache.enabled:true}") boolean enabled) {
        this.redis = redis;
        this.cipher = cipher;
        this.json = json;
        this.clock = clock;
        this.metrics = metrics;
        this.enabled = enabled;
    }

    /** Operator kill-switch via {@code yaya.agentic.auth-resolver.cache.enabled=false}. */
    public boolean isEnabled() { return enabled; }

    /**
     * Look up a cached JWT for the triple. Returns empty when:
     * <ul>
     *   <li>The cache is disabled.</li>
     *   <li>Redis has no key (natural miss).</li>
     *   <li>Redis is unreachable (logged as {@code redisUnavailable},
     *       caller treats as miss).</li>
     *   <li>The decrypted JWT is past its own {@code exp} — defense in
     *       depth against Redis TTL clock skew per §13.6 greptest.</li>
     * </ul>
     */
    public Optional<String> get(CacheKey key) {
        if (!enabled) { metrics.miss(key.tenant().value()); return Optional.empty(); }
        String rawJson;
        try {
            rawJson = redis.opsForValue().get(key.redisKey());
        } catch (RuntimeException e) {
            log.warn("auth-resolver cache GET failed (Redis unreachable?): {}", e.getMessage());
            metrics.redisUnavailable(key.tenant().value());
            return Optional.empty();
        }
        if (rawJson == null) {
            metrics.miss(key.tenant().value());
            return Optional.empty();
        }
        String jwt;
        try {
            Envelope env = json.readValue(rawJson, Envelope.class);
            jwt = cipher.decrypt(env.ct(), env.iv());
        } catch (Exception e) {
            // Corrupted row — drop it and miss. A regression in the cipher
            // key would surface here; we don't want the cache to keep
            // returning broken values.
            log.warn("auth-resolver cache decrypt failed for key '{}' — dropping",
                    redactedKey(key));
            redis.delete(key.redisKey());
            metrics.miss(key.tenant().value());
            return Optional.empty();
        }
        Instant exp = parseExp(jwt);
        if (exp == null || !exp.isAfter(Instant.now(clock))) {
            metrics.expiredOnRead(key.tenant().value());
            redis.delete(key.redisKey());
            return Optional.empty();
        }
        metrics.hit(key.tenant().value());
        return Optional.of(jwt);
    }

    /**
     * Cache a resolver-minted JWT for the triple. Skipped when:
     * <ul>
     *   <li>The cache is disabled.</li>
     *   <li>The JWT has no parseable {@code exp} — without an upper bound
     *       on lifetime there's no safe TTL to set.</li>
     *   <li>The computed TTL after subtracting {@code refreshWindowSeconds}
     *       is non-positive — the token is already inside its refresh
     *       window or past expiry, so caching would serve a token the
     *       caller is about to refresh anyway.</li>
     * </ul>
     *
     * @param refreshWindowSeconds  per-resolver knob; the cache writes
     *                              a TTL of {@code min(exp-now, maxCacheSecondsOverride)
     *                              - refreshWindowSeconds}.
     * @param maxCacheSecondsOverride  optional ceiling — for tenants who
     *                              issue long-lived tokens but want
     *                              identity re-checked more often.
     */
    public void put(CacheKey key, String jwt,
                    int refreshWindowSeconds,
                    Integer maxCacheSecondsOverride) {
        if (!enabled) return;
        if (jwt == null || jwt.isBlank()) return;
        Instant exp = parseExp(jwt);
        Instant now = Instant.now(clock);
        if (exp == null) {
            log.debug("cache put: JWT has no parseable exp — skipping");
            return;
        }
        long secsToExp = Duration.between(now, exp).toSeconds();
        if (maxCacheSecondsOverride != null && maxCacheSecondsOverride > 0) {
            secsToExp = Math.min(secsToExp, maxCacheSecondsOverride);
        }
        long ttl = secsToExp - refreshWindowSeconds;
        if (ttl <= 0) {
            log.debug("cache put: ttl after refresh window is {}s — skipping", ttl);
            return;
        }
        JwtSecretCipher.Encrypted enc = cipher.encrypt(jwt);
        String rawJson;
        try {
            rawJson = json.writeValueAsString(new Envelope(enc.ciphertext(), enc.iv()));
        } catch (Exception e) {
            throw new IllegalStateException("could not encode cache envelope", e);
        }
        try {
            redis.opsForValue().set(key.redisKey(), rawJson, Duration.ofSeconds(ttl));
        } catch (RuntimeException e) {
            log.warn("auth-resolver cache PUT failed (Redis unreachable?): {}", e.getMessage());
            metrics.redisUnavailable(key.tenant().value());
        }
    }

    /** Single-key eviction. The §8 reactive 401 path + 404 on refresh +
     *  the admin invalidate-cache endpoint all funnel through here. */
    public void evict(CacheKey key) {
        if (!enabled) return;
        try {
            redis.delete(key.redisKey());
            metrics.invalidate(key.tenant().value());
        } catch (RuntimeException e) {
            log.warn("auth-resolver cache DEL failed (Redis unreachable?): {}", e.getMessage());
            metrics.redisUnavailable(key.tenant().value());
        }
    }

    /** Pattern-scoped eviction; used when an admin deletes a resolver row
     *  per §8.5. Returns the number of keys deleted so the admin endpoint
     *  can report it. */
    public long evictAllForTenant(Ids.TenantId tenant) {
        if (!enabled) return 0L;
        long deleted = 0L;
        String pattern = KEY_PREFIX + tenant.value() + ":*";
        try (Cursor<String> cursor = redis.scan(
                ScanOptions.scanOptions().match(pattern).count(256).build())) {
            while (cursor.hasNext()) {
                redis.delete(cursor.next());
                deleted++;
            }
        } catch (RuntimeException e) {
            log.warn("auth-resolver cache SCAN-evict failed for tenant '{}': {}",
                    tenant.value(), e.getMessage());
            metrics.redisUnavailable(tenant.value());
        }
        if (deleted > 0) metrics.invalidate(tenant.value());
        return deleted;
    }

    private static Instant parseExp(String jwt) {
        try {
            SignedJWT parsed = SignedJWT.parse(jwt);
            JWTClaimsSet claims = parsed.getJWTClaimsSet();
            if (claims == null || claims.getExpirationTime() == null) return null;
            return claims.getExpirationTime().toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    /** Don't log raw caller phones — log the hashed suffix only, matching
     *  what's actually in Redis. */
    private static String redactedKey(CacheKey k) {
        return KEY_PREFIX + k.tenant().value() + ":" + k.sender().value() + ":<sha256>";
    }

    /**
     * Triple that uniquely identifies a (tenant, sender, caller) JWT.
     *
     * <p>The Redis key derives from {@link #redisKey()} —
     * {@code auth-resolver-token:{tenantId}:{senderId}:{sha256_lower_hex(caller)}}.
     * Raw phone numbers never appear in the keyspace.
     */
    public record CacheKey(Ids.TenantId tenant, SenderId sender, String e164CallerPhone) {

        public CacheKey {
            if (tenant == null) throw new IllegalArgumentException("tenant required");
            if (sender == null) throw new IllegalArgumentException("sender required");
            if (e164CallerPhone == null || e164CallerPhone.isBlank()) {
                throw new IllegalArgumentException("e164CallerPhone required");
            }
        }

        public String redisKey() {
            return KEY_PREFIX + tenant.value() + ":"
                    + sender.value() + ":"
                    + sha256LowerHex(e164CallerPhone);
        }
    }

    public static String sha256LowerHex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available?", e);
        }
    }

    /** JSON envelope written to Redis. AES-256-GCM ciphertext + IV, both
     *  base64. */
    record Envelope(String ct, String iv) {}
}
