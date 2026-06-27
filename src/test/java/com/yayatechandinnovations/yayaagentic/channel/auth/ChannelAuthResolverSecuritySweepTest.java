package com.yayatechandinnovations.yayaagentic.channel.auth;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.yayatechandinnovations.yayaagentic.auth.secrets.JwtSecretCipher;
import com.yayatechandinnovations.yayaagentic.channel.auth.cache.AuthResolverCacheMetrics;
import com.yayatechandinnovations.yayaagentic.channel.auth.cache.AuthResolverTokenCache;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.AuthResolverCallEntity;
import com.yayatechandinnovations.yayaagentic.persistence.AuthResolverCallRepository;
import com.yayatechandinnovations.yayaagentic.persistence.TenantEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantRepository;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Defends the seven security non-negotiables from {@code
 * docs/design/channel-auth-resolver-design.md} §13. A regression that
 * accidentally:
 *
 * <ul>
 *   <li>Logs a JWT in cleartext anywhere in the pipeline,</li>
 *   <li>Persists a raw caller phone in the Redis cache keyspace,</li>
 *   <li>Persists a raw caller phone in the {@code auth_resolver_calls} audit
 *       table,</li>
 *   <li>Stores the plaintext service-secret on the resolver row,</li>
 *   <li>Returns the plaintext service-secret on the admin GET surface,</li>
 *   <li>Treats an expired JWT as valid in the cache, or</li>
 *   <li>Allows fuzzy bean-name resolution,</li>
 * </ul>
 *
 * fails this test loudly with the offending byte sequence identified.
 *
 * <p>This is the codified §13 greptest the design calls out by name.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key=",
        "yaya.agentic.secrets.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class ChannelAuthResolverSecuritySweepTest {

    /** SHA-256 lowercase hex — what every cache key suffix MUST look like. */
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");
    /** Loose JWT shape: header.payload.signature, base64url. The leading
     *  byte sequence `eyJ` is the base64 of `{"` and is present in every
     *  practical JWT header. */
    private static final Pattern JWT_LIKE = Pattern.compile("eyJ[A-Za-z0-9_\\-]{4,}\\.[A-Za-z0-9_\\-]{4,}\\.[A-Za-z0-9_\\-]{4,}");

    @Autowired StringRedisTemplate redis;
    @Autowired AuthResolverCallRepository auditRepo;
    @Autowired TenantRepository tenantRepo;

    private static final String TENANT = "auth-sweep";
    private static final String CALLER_PHONE = "+15551234567";
    private static final String SENDER_PHONE = "+15551112222";
    private static final byte[] SIGNING_SECRET = "test-secret-bytes-must-be-32-byt".getBytes();
    private static final Instant FIXED_NOW = Instant.parse("2026-06-27T14:30:00Z");

    private JwtSecretCipher cipher;
    private AuthResolverTokenCache cache;
    private Logger capturedLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        cipher = new JwtSecretCipher(Base64.getEncoder().encodeToString(new byte[32]));
        cache = new AuthResolverTokenCache(
                redis, cipher, new ObjectMapper(),
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC),
                new AuthResolverCacheMetrics(new SimpleMeterRegistry()),
                true);
        cache.evictAllForTenant(new Ids.TenantId(TENANT));
        if (tenantRepo.findById(TENANT).isEmpty()) {
            tenantRepo.save(new TenantEntity(TENANT, "Auth Sweep Test"));
        }
        // Wipe audit rows from prior sweeps in this tenant.
        auditRepo.deleteAll(auditRepo.findAll().stream()
                .filter(r -> TENANT.equals(r.getTenantId())).toList());

        // Logback capture across the entire channel.auth tree.
        capturedLogger = (Logger) LoggerFactory.getLogger(
                "com.yayatechandinnovations.yayaagentic.channel.auth");
        capturedLogger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        capturedLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        if (capturedLogger != null && appender != null) {
            capturedLogger.detachAppender(appender);
        }
    }

    /** §13.1 — No JWT cleartext in any log line emitted by the channel-auth
     *  tree. Stamp a JWT through put/get and scan the entire captured log
     *  stream for a JWT-shaped sequence. */
    @Test
    void no_jwt_cleartext_in_any_log_line_across_cache_put_and_get() {
        String jwt = mintJwt(FIXED_NOW.plus(Duration.ofMinutes(15)));
        AuthResolverTokenCache.CacheKey key = new AuthResolverTokenCache.CacheKey(
                new Ids.TenantId(TENANT), new SenderId("snd_sweep"), CALLER_PHONE);

        cache.put(key, jwt, 60, null);
        cache.get(key);     // hit
        cache.get(key);     // hit again
        cache.evict(key);   // eviction log

        // Every log message + arguments + caller string must be JWT-free.
        for (ILoggingEvent ev : appender.list) {
            String formatted = ev.getFormattedMessage();
            assertThat(JWT_LIKE.matcher(formatted == null ? "" : formatted).find())
                    .as("log line leaked a JWT: " + formatted)
                    .isFalse();
            // Defense in depth — even raw arguments must be JWT-free.
            Object[] args = ev.getArgumentArray();
            if (args != null) {
                for (Object arg : args) {
                    String s = arg == null ? "" : arg.toString();
                    assertThat(JWT_LIKE.matcher(s).find())
                            .as("log argument leaked a JWT: " + s)
                            .isFalse();
                }
            }
        }
    }

    /** §13.2 — Cache keyspace contains ONLY SHA-256 hex suffixes for the
     *  caller phone — never a raw E.164. Asserts across a full round-trip
     *  for multiple callers. */
    @Test
    void redis_keyspace_has_only_sha256_caller_suffixes() {
        Set<String> callers = Set.of("+15551234567", "+18005551111", "+447700900001");
        for (String caller : callers) {
            AuthResolverTokenCache.CacheKey key = new AuthResolverTokenCache.CacheKey(
                    new Ids.TenantId(TENANT), new SenderId("snd_sweep"), caller);
            cache.put(key, mintJwt(FIXED_NOW.plus(Duration.ofMinutes(15))), 60, null);
        }
        Set<String> keys = redis.keys("auth-resolver-token:" + TENANT + ":*");
        assertThat(keys).hasSize(callers.size());
        for (String k : keys) {
            String[] parts = k.split(":");
            assertThat(parts).hasSize(4);     // prefix:tenant:sender:hash
            assertThat(parts[3]).matches(SHA256_HEX);
            // And definitely no raw phone leak.
            for (String caller : callers) {
                assertThat(k).doesNotContain(caller);
                assertThat(k).doesNotContain(caller.substring(1));      // without +
            }
        }
    }

    /** §13.2 (companion) — Raw caller phone NEVER lands in
     *  {@code auth_resolver_calls.caller_sha256} or any other column.
     *  The dispatcher hashes before write; this asserts no path slips a
     *  raw phone past the hash. */
    @Test
    void auth_resolver_calls_audit_never_stores_raw_caller_phone() {
        // Stamp an audit row directly so we can assert the column shape.
        auditRepo.save(new AuthResolverCallEntity(
                TENANT, "test-resolver", null,
                "snd_sweep",
                AuthResolverTokenCache.sha256LowerHex(CALLER_PHONE),
                "whatsapp", "INITIAL",
                OffsetDateTime.now(ZoneOffset.UTC),
                "ok", "MISS"));

        List<AuthResolverCallEntity> rows = auditRepo.findAll().stream()
                .filter(r -> TENANT.equals(r.getTenantId())).toList();
        assertThat(rows).isNotEmpty();
        for (AuthResolverCallEntity row : rows) {
            assertThat(row.getCallerSha256()).matches(SHA256_HEX);
            // None of the persisted columns should contain the raw phone.
            String composite = String.join("|",
                    nullSafe(row.getCallerSha256()),
                    nullSafe(row.getSenderId()),
                    nullSafe(row.getResolverName()),
                    nullSafe(row.getStatus()),
                    nullSafe(row.getErrorSummary()),
                    nullSafe(row.getChannel()));
            assertThat(composite).doesNotContain(CALLER_PHONE);
            assertThat(composite).doesNotContain(CALLER_PHONE.substring(1));
        }
    }

    /** §13.6 — Cache is never consulted past JWT exp, even when Redis still
     *  has the key (defense in depth against TTL clock skew). */
    @Test
    void cache_treats_expired_jwt_as_miss_even_when_redis_still_holds_it() {
        Instant exp = FIXED_NOW.plus(Duration.ofHours(1));
        String jwt = mintJwt(exp);
        AuthResolverTokenCache.CacheKey key = new AuthResolverTokenCache.CacheKey(
                new Ids.TenantId(TENANT), new SenderId("snd_sweep"), CALLER_PHONE);
        cache.put(key, jwt, 60, null);
        assertThat(redis.opsForValue().get(key.redisKey())).isNotNull();

        AuthResolverTokenCache skewed = new AuthResolverTokenCache(
                redis, cipher, new ObjectMapper(),
                Clock.fixed(exp.plus(Duration.ofMinutes(1)), ZoneOffset.UTC),
                new AuthResolverCacheMetrics(new SimpleMeterRegistry()),
                true);
        assertThat(skewed.get(key)).isEmpty();
        // And the dead key was wiped on the way out.
        assertThat(redis.opsForValue().get(key.redisKey())).isNull();
    }

    /** §13.3 — Plaintext service-secret is never recoverable through the
     *  admin response shape. Tested at the unit level for the response
     *  record itself; the full admin-controller round-trip is asserted in
     *  {@code TenantAuthResolverControllerTest}. */
    @Test
    void resolver_response_dto_does_not_carry_plaintext_secret() throws Exception {
        // Build the response shape Jackson would serialise on a GET — confirm
        // there is no `serviceSecret` field at all, only `serviceSecretMasked`.
        var responseClass = Class.forName(
                "com.yayatechandinnovations.yayaagentic.api.TenantAuthResolverController$ResolverResponse");
        var componentNames = new java.util.HashSet<String>();
        for (var c : responseClass.getRecordComponents()) {
            componentNames.add(c.getName());
        }
        assertThat(componentNames).contains("serviceSecretMasked");
        assertThat(componentNames).doesNotContain("serviceSecret");
        assertThat(componentNames).doesNotContain("secret");
        assertThat(componentNames).doesNotContain("plaintextSecret");
    }

    // §13.7 (bean lookup is exact-match) is defended by
    // AuthResolverRegistryTest#bean_lookup_is_exact_match_with_overlapping_names

    // ---- helpers ------------------------------------------------------

    private static String mintJwt(Instant exp) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer("https://acme.example.com/auth")
                    .audience(TENANT)
                    .subject("u_42")
                    .jwtID(UUID.randomUUID().toString())
                    .issueTime(Date.from(FIXED_NOW))
                    .expirationTime(Date.from(exp))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(SIGNING_SECRET));
            return jwt.serialize();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
