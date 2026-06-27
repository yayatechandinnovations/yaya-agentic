package com.yayatechandinnovations.yayaagentic.channel.auth.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.yayatechandinnovations.yayaagentic.auth.secrets.JwtSecretCipher;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trip coverage of {@link AuthResolverTokenCache} against a real
 * Testcontainers Redis. Validates the §13 greptest non-negotiables:
 * SHA-256 keying (no raw caller phones in keyspace), defense-in-depth
 * exp recheck on read, single + pattern eviction.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
class AuthResolverTokenCacheTest {

    private static final byte[] SIGNING_SECRET = "test-secret-bytes-must-be-32-byt".getBytes();

    @Autowired StringRedisTemplate redis;

    private Instant fixedNow;
    private AuthResolverTokenCache cache;
    private JwtSecretCipher cipher;

    @BeforeEach
    void setUp() {
        fixedNow = Instant.parse("2026-06-27T14:30:00Z");
        cipher = new JwtSecretCipher(
                Base64.getEncoder().encodeToString(new byte[32]));
        cache = new AuthResolverTokenCache(
                redis, cipher, new ObjectMapper(),
                Clock.fixed(fixedNow, ZoneOffset.UTC),
                new AuthResolverCacheMetrics(new SimpleMeterRegistry()),
                true);
        // Wipe out any prior test leftovers under the auth-resolver-token namespace.
        cache.evictAllForTenant(new Ids.TenantId("acme"));
        cache.evictAllForTenant(new Ids.TenantId("other"));
    }

    @Test
    void put_then_get_round_trips_the_jwt() {
        AuthResolverTokenCache.CacheKey key = keyFor("acme", "snd_acme_main", "+15551234567");
        String jwt = mintJwt(fixedNow.plus(Duration.ofMinutes(15)));

        cache.put(key, jwt, 60, null);
        Optional<String> got = cache.get(key);

        assertThat(got).contains(jwt);
    }

    @Test
    void redis_key_uses_sha256_of_caller_phone() {
        AuthResolverTokenCache.CacheKey key = keyFor("acme", "snd_acme_main", "+15551234567");
        String jwt = mintJwt(fixedNow.plus(Duration.ofMinutes(15)));
        cache.put(key, jwt, 60, null);

        Set<String> keys = redis.keys("auth-resolver-token:acme:snd_acme_main:*");
        assertThat(keys).hasSize(1);
        String stored = keys.iterator().next();
        // Suffix is 64 lowercase hex chars (SHA-256), not a phone number.
        assertThat(stored).matches("auth-resolver-token:acme:snd_acme_main:[0-9a-f]{64}");
        assertThat(stored).doesNotContain("+1555");
        assertThat(stored).doesNotContain("1234567");
    }

    @Test
    void miss_returns_empty() {
        Optional<String> got = cache.get(keyFor("acme", "snd_acme_main", "+15559999999"));
        assertThat(got).isEmpty();
    }

    @Test
    void cached_value_is_encrypted_at_rest() {
        AuthResolverTokenCache.CacheKey key = keyFor("acme", "snd_acme_main", "+15551234567");
        String jwt = mintJwt(fixedNow.plus(Duration.ofMinutes(15)));
        cache.put(key, jwt, 60, null);

        String storedJson = redis.opsForValue().get(key.redisKey());
        // Defense in depth: a Redis dump must not surface the JWT — only
        // the AES-256-GCM envelope's ciphertext + IV.
        assertThat(storedJson).doesNotContain(jwt);
        assertThat(storedJson).contains("\"ct\"").contains("\"iv\"");
    }

    @Test
    void get_treats_expired_jwt_as_miss_even_when_redis_key_still_present() {
        // Mint a JWT that expires in 1h, put it in Redis with a TTL of 30m
        // (deliberately decoupling Redis TTL from JWT exp). Then advance
        // the cache's Clock past the JWT's exp and assert get() returns
        // empty even though Redis still holds the key.
        AuthResolverTokenCache.CacheKey key = keyFor("acme", "snd_acme_main", "+15551234567");
        Instant exp = fixedNow.plus(Duration.ofHours(1));
        String jwt = mintJwt(exp);
        cache.put(key, jwt, 60, null);

        // Confirm Redis still holds it RIGHT NOW.
        assertThat(redis.opsForValue().get(key.redisKey())).isNotNull();

        // Skew the cache's clock to 1 minute past the JWT's exp.
        AuthResolverTokenCache skewedCache = new AuthResolverTokenCache(
                redis, cipher, new ObjectMapper(),
                Clock.fixed(exp.plus(Duration.ofMinutes(1)), ZoneOffset.UTC),
                new AuthResolverCacheMetrics(new SimpleMeterRegistry()),
                true);
        Optional<String> got = skewedCache.get(key);

        assertThat(got).isEmpty();
        // And the dead key was evicted on the way out.
        assertThat(redis.opsForValue().get(key.redisKey())).isNull();
    }

    @Test
    void evict_drops_the_key() {
        AuthResolverTokenCache.CacheKey key = keyFor("acme", "snd_acme_main", "+15551234567");
        cache.put(key, mintJwt(fixedNow.plus(Duration.ofMinutes(15))), 60, null);
        assertThat(cache.get(key)).isPresent();

        cache.evict(key);

        assertThat(cache.get(key)).isEmpty();
    }

    @Test
    void evict_all_for_tenant_matches_only_that_tenant() {
        cache.put(keyFor("acme", "snd_acme", "+15551"),
                mintJwt(fixedNow.plus(Duration.ofMinutes(15))), 60, null);
        cache.put(keyFor("acme", "snd_acme", "+15552"),
                mintJwt(fixedNow.plus(Duration.ofMinutes(15))), 60, null);
        cache.put(keyFor("other", "snd_other", "+15553"),
                mintJwt(fixedNow.plus(Duration.ofMinutes(15))), 60, null);

        long deleted = cache.evictAllForTenant(new Ids.TenantId("acme"));

        assertThat(deleted).isEqualTo(2L);
        assertThat(cache.get(keyFor("acme", "snd_acme", "+15551"))).isEmpty();
        assertThat(cache.get(keyFor("other", "snd_other", "+15553"))).isPresent();
    }

    @Test
    void put_skips_when_token_inside_refresh_window() {
        AuthResolverTokenCache.CacheKey key = keyFor("acme", "snd_acme_main", "+15551234567");
        // JWT exp is 30s away but refresh window is 60s — the cache would
        // store with a negative TTL, so the design says: skip the write.
        String jwt = mintJwt(fixedNow.plus(Duration.ofSeconds(30)));
        cache.put(key, jwt, 60, null);

        assertThat(redis.opsForValue().get(key.redisKey())).isNull();
    }

    @Test
    void put_caps_ttl_at_max_cache_seconds_override() {
        AuthResolverTokenCache.CacheKey key = keyFor("acme", "snd_acme_main", "+15551234567");
        // JWT exp = 1h, but the resolver wants identity re-checked every 90s.
        String jwt = mintJwt(fixedNow.plus(Duration.ofHours(1)));
        cache.put(key, jwt, 30, 90);

        Long ttl = redis.getExpire(key.redisKey());
        assertThat(ttl).isNotNull();
        // Storage TTL should be ~60s (90 - 30), not ~3540s.
        assertThat(ttl).isBetween(50L, 60L);
    }

    @Test
    void disabled_cache_returns_empty_for_every_get() {
        AuthResolverTokenCache disabled = new AuthResolverTokenCache(
                redis, cipher, new ObjectMapper(),
                Clock.fixed(fixedNow, ZoneOffset.UTC),
                new AuthResolverCacheMetrics(new SimpleMeterRegistry()),
                false);
        AuthResolverTokenCache.CacheKey key = keyFor("acme", "snd_acme_main", "+15551234567");
        disabled.put(key, mintJwt(fixedNow.plus(Duration.ofMinutes(15))), 60, null);

        assertThat(disabled.get(key)).isEmpty();
        // And the write was skipped too — nothing in Redis.
        assertThat(redis.opsForValue().get(key.redisKey())).isNull();
    }

    // ---- helpers ------------------------------------------------------

    private static AuthResolverTokenCache.CacheKey keyFor(String tenant, String sender,
                                                          String callerPhone) {
        return new AuthResolverTokenCache.CacheKey(
                new Ids.TenantId(tenant), new SenderId(sender), callerPhone);
    }

    private static String mintJwt(Instant exp) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer("https://acme.example.com/auth")
                    .audience("acme")
                    .subject("u_42")
                    .jwtID(UUID.randomUUID().toString())
                    .issueTime(new Date())
                    .expirationTime(Date.from(exp))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(SIGNING_SECRET));
            return jwt.serialize();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
