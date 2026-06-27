package com.yayatechandinnovations.yayaagentic.channel.auth.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.yayatechandinnovations.yayaagentic.auth.secrets.JwtSecretCipher;
import com.yayatechandinnovations.yayaagentic.channel.auth.AuthResolver;
import com.yayatechandinnovations.yayaagentic.channel.auth.AuthResolverException;
import com.yayatechandinnovations.yayaagentic.channel.auth.AuthResolverRegistry;
import com.yayatechandinnovations.yayaagentic.channel.auth.HttpAuthResolverFactory;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.AuthResolverCallEntity;
import com.yayatechandinnovations.yayaagentic.persistence.AuthResolverCallRepository;
import com.yayatechandinnovations.yayaagentic.persistence.ChannelAuthResolverEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChannelAuthResolverRepository;
import com.yayatechandinnovations.yayaagentic.persistence.TenantEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantRepository;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage of the cache → resolver → audit pipeline. Uses
 * Testcontainers Redis for the cache and stubs the resolver to drive the
 * three outcome paths (token issued, unresolved, failed).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
class AuthResolverDispatcherTest {

    private static final byte[] SIGNING_SECRET = "test-secret-bytes-must-be-32-byt".getBytes();
    private static final Instant FIXED_NOW = Instant.parse("2026-06-27T14:30:00Z");

    @Autowired StringRedisTemplate redis;
    @Autowired AuthResolverCallRepository auditRepo;
    @Autowired TenantRepository tenantRepo;

    private JwtSecretCipher cipher;
    private AuthResolverTokenCache cache;

    @BeforeEach
    void setUp() {
        cipher = new JwtSecretCipher(Base64.getEncoder().encodeToString(new byte[32]));
        cache = new AuthResolverTokenCache(
                redis, cipher, new ObjectMapper(),
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC),
                new AuthResolverCacheMetrics(new SimpleMeterRegistry()),
                true);
        cache.evictAllForTenant(new Ids.TenantId("acme"));
        // Audit rows FK to tenants — make sure the tenant exists.
        if (tenantRepo.findById("acme").isEmpty()) {
            tenantRepo.save(new TenantEntity("acme", "Acme Test Tenant"));
        }
        // Wipe audit rows from prior tests so "latest" assertions don't see
        // them as the most recent row.
        auditRepo.deleteAll(auditRepo.findAll().stream()
                .filter(r -> "acme".equals(r.getTenantId())).toList());
    }

    @Test
    void cache_hit_short_circuits_the_resolver() {
        AtomicInteger calls = new AtomicInteger();
        AuthResolverDispatcher dispatcher = dispatcherWith(stubBean("acme-lookup",
                r -> { calls.incrementAndGet(); return Optional.of(mintJwt(15)); }));

        // First call — cache miss, resolver fires.
        AuthResolverDispatcher.Result r1 = dispatch(dispatcher, AuthResolver.ResolveReason.INITIAL);
        assertThat(r1).isInstanceOf(AuthResolverDispatcher.Result.TokenIssued.class);
        assertThat(((AuthResolverDispatcher.Result.TokenIssued) r1).cacheState())
                .isEqualTo(AuthResolverDispatcher.CacheState.MISS);
        assertThat(calls.get()).isEqualTo(1);

        // Second call — cache hit, resolver does NOT fire.
        AuthResolverDispatcher.Result r2 = dispatch(dispatcher, AuthResolver.ResolveReason.INITIAL);
        assertThat(r2).isInstanceOf(AuthResolverDispatcher.Result.TokenIssued.class);
        assertThat(((AuthResolverDispatcher.Result.TokenIssued) r2).cacheState())
                .isEqualTo(AuthResolverDispatcher.CacheState.HIT);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void refresh_always_fires_resolver_even_when_cache_has_value() {
        AtomicInteger calls = new AtomicInteger();
        AuthResolverDispatcher dispatcher = dispatcherWith(stubBean("acme-lookup",
                r -> { calls.incrementAndGet(); return Optional.of(mintJwt(15)); }));

        dispatch(dispatcher, AuthResolver.ResolveReason.INITIAL);     // miss, populates
        dispatch(dispatcher, AuthResolver.ResolveReason.REFRESH);     // forced refresh

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void empty_resolver_response_returns_Unresolved() {
        AuthResolverDispatcher dispatcher = dispatcherWith(stubBean("acme-lookup",
                r -> Optional.empty()));

        AuthResolverDispatcher.Result r = dispatch(dispatcher, AuthResolver.ResolveReason.INITIAL);

        assertThat(r).isInstanceOf(AuthResolverDispatcher.Result.Unresolved.class);
    }

    @Test
    void resolver_exception_returns_Failed_with_status() {
        AuthResolverDispatcher dispatcher = dispatcherWith(stubBean("acme-lookup",
                r -> { throw new AuthResolverException("timeout",
                        "resolver timed out", 0, null); }));

        AuthResolverDispatcher.Result r = dispatch(dispatcher, AuthResolver.ResolveReason.INITIAL);

        assertThat(r).isInstanceOf(AuthResolverDispatcher.Result.Failed.class);
        assertThat(((AuthResolverDispatcher.Result.Failed) r).status()).isEqualTo("timeout");
    }

    @Test
    void refresh_404_evicts_existing_cache_entry() {
        AtomicInteger nth = new AtomicInteger();
        // First call returns a valid token. Second call (the refresh) returns 404.
        AuthResolverDispatcher dispatcher = dispatcherWith(stubBean("acme-lookup",
                r -> nth.getAndIncrement() == 0
                        ? Optional.of(mintJwt(15))
                        : Optional.empty()));

        dispatch(dispatcher, AuthResolver.ResolveReason.INITIAL);
        AuthResolverTokenCache.CacheKey key = new AuthResolverTokenCache.CacheKey(
                new Ids.TenantId("acme"), new SenderId("snd_acme"), "+15551234567");
        assertThat(cache.get(key)).isPresent();

        AuthResolverDispatcher.Result r = dispatch(dispatcher, AuthResolver.ResolveReason.REFRESH);
        assertThat(r).isInstanceOf(AuthResolverDispatcher.Result.Unresolved.class);
        // §8.3 — 404 on refresh evicts the cache entry.
        assertThat(cache.get(key)).isEmpty();
    }

    @Test
    void deactivated_resolver_row_returns_Failed_and_writes_audit() {
        AuthResolverDispatcher dispatcher = dispatcherWith(stubBean("acme-lookup",
                r -> Optional.of(mintJwt(15))));
        // Find the row we just stored and flip its status.
        ChannelAuthResolverRepository repo = ((StubResolverRepo) repoRef(dispatcher)).delegate;
        ChannelAuthResolverEntity row = repo.findByTenantIdAndName("acme", "acme-lookup").orElseThrow();
        row.setStatus("DEACTIVATED");

        long auditBefore = auditRepo.count();
        AuthResolverDispatcher.Result r = dispatch(dispatcher, AuthResolver.ResolveReason.INITIAL);

        assertThat(r).isInstanceOf(AuthResolverDispatcher.Result.Failed.class);
        assertThat(auditRepo.count()).isGreaterThan(auditBefore);
        // The most recent audit row carries config_error.
        AuthResolverCallEntity latest = auditRepo.findAll().stream()
                .max((a, b) -> a.getStartedAt().compareTo(b.getStartedAt()))
                .orElseThrow();
        assertThat(latest.getStatus()).isEqualTo("config_error");
    }

    @Test
    void successful_resolve_writes_audit_with_ok_status_and_elapsed() {
        AuthResolverDispatcher dispatcher = dispatcherWith(stubBean("acme-lookup",
                r -> Optional.of(mintJwt(15))));

        dispatch(dispatcher, AuthResolver.ResolveReason.INITIAL);
        AuthResolverCallEntity latest = auditRepo.findAll().stream()
                .max((a, b) -> a.getStartedAt().compareTo(b.getStartedAt()))
                .orElseThrow();
        assertThat(latest.getStatus()).isEqualTo("ok");
        assertThat(latest.getCacheState()).isEqualTo("MISS");
        // §13.2 — caller phone is hashed, never raw.
        assertThat(latest.getCallerSha256()).matches("[0-9a-f]{64}");
        assertThat(latest.getCallerSha256()).doesNotContain("1234567");
    }

    // ---- harness ------------------------------------------------------

    /** Build a dispatcher backed by an in-process resolver row store with
     *  one BEAN-kind row pointing at the supplied stub bean. */
    private AuthResolverDispatcher dispatcherWith(AuthResolver bean) {
        StubResolverRepo repo = new StubResolverRepo();
        ChannelAuthResolverEntity row = new ChannelAuthResolverEntity(
                "acme", bean.name(), null, "BEAN");
        row.setBeanName(bean.name());
        setField(row, "id", 1L);
        repo.put(row);

        HttpAuthResolverFactory neverFactory = new HttpAuthResolverFactory(
                null, null, null, null, null) {
            @Override
            public com.yayatechandinnovations.yayaagentic.channel.auth.HttpAuthResolver build(
                    ChannelAuthResolverEntity r) {
                throw new IllegalStateException("HTTP factory not expected");
            }
        };
        AuthResolverRegistry registry = new AuthResolverRegistry(
                repo, neverFactory, List.of(bean));

        return new AuthResolverDispatcher(registry, repo, cache, auditRepo,
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    private AuthResolverDispatcher.Result dispatch(
            AuthResolverDispatcher dispatcher, AuthResolver.ResolveReason reason) {
        return dispatcher.dispatch(
                1L,
                new Ids.TenantId("acme"),
                new SenderId("snd_acme"),
                "+15551112222",
                "+15551234567",
                new Ids.SessionId(UUID.randomUUID().toString()),
                "whatsapp",
                reason);
    }

    private AuthResolver stubBean(String name, Function<AuthResolver.ResolveRequest, Optional<String>> fn) {
        return new AuthResolver() {
            @Override public String name() { return name; }
            @Override public Optional<String> resolve(ResolveRequest r) { return fn.apply(r); }
        };
    }

    private static String mintJwt(int minutesToExp) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer("https://acme.example.com/auth")
                    .audience("acme")
                    .subject("u_42")
                    .jwtID(UUID.randomUUID().toString())
                    .issueTime(Date.from(FIXED_NOW))
                    .expirationTime(Date.from(FIXED_NOW.plus(Duration.ofMinutes(minutesToExp))))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(SIGNING_SECRET));
            return jwt.serialize();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static ChannelAuthResolverRepository repoRef(AuthResolverDispatcher d) {
        try {
            Field f = AuthResolverDispatcher.class.getDeclaredField("resolverRepo");
            f.setAccessible(true);
            return (ChannelAuthResolverRepository) f.get(d);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void setField(Object o, String name, Object value) {
        try {
            Field f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(o, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Tiny in-memory ChannelAuthResolverRepository — only the methods
     *  the dispatcher uses are implemented; everything else throws. */
    private static class StubResolverRepo implements ChannelAuthResolverRepository {
        final java.util.Map<Long, ChannelAuthResolverEntity> byId = new java.util.HashMap<>();
        /** Allows the test to mutate the stored row (e.g. flip status). */
        final ChannelAuthResolverRepository delegate = this;
        void put(ChannelAuthResolverEntity e) { byId.put(e.getId(), e); }

        @Override public Optional<ChannelAuthResolverEntity> findById(Long id) {
            return Optional.ofNullable(byId.get(id));
        }
        @Override public Optional<ChannelAuthResolverEntity> findByTenantIdAndName(
                String t, String n) {
            return byId.values().stream()
                    .filter(e -> e.getTenantId().equals(t) && e.getName().equals(n))
                    .findFirst();
        }
        @Override public List<ChannelAuthResolverEntity> findByTenantIdOrderByNameAsc(String t) {
            return List.of();
        }
        @Override public List<ChannelAuthResolverEntity> findByTenantIdAndStatusOrderByNameAsc(
                String t, String s) { return List.of(); }
        @Override public List<ChannelAuthResolverEntity> findAll() { throw nope(); }
        @Override public List<ChannelAuthResolverEntity> findAll(org.springframework.data.domain.Sort s) { throw nope(); }
        @Override public List<ChannelAuthResolverEntity> findAllById(Iterable<Long> ids) { throw nope(); }
        @Override public <S extends ChannelAuthResolverEntity> List<S> saveAll(Iterable<S> es) { throw nope(); }
        @Override public void flush() {}
        @Override public <S extends ChannelAuthResolverEntity> S saveAndFlush(S e) { throw nope(); }
        @Override public <S extends ChannelAuthResolverEntity> List<S> saveAllAndFlush(Iterable<S> es) { throw nope(); }
        @Override public void deleteAllInBatch(Iterable<ChannelAuthResolverEntity> es) { throw nope(); }
        @Override public void deleteAllByIdInBatch(Iterable<Long> ids) { throw nope(); }
        @Override public void deleteAllInBatch() { throw nope(); }
        @Override public ChannelAuthResolverEntity getOne(Long id) { throw nope(); }
        @Override public ChannelAuthResolverEntity getById(Long id) { throw nope(); }
        @Override public ChannelAuthResolverEntity getReferenceById(Long id) { throw nope(); }
        @Override public <S extends ChannelAuthResolverEntity> List<S> findAll(org.springframework.data.domain.Example<S> e) { throw nope(); }
        @Override public <S extends ChannelAuthResolverEntity> List<S> findAll(org.springframework.data.domain.Example<S> e, org.springframework.data.domain.Sort s) { throw nope(); }
        @Override public org.springframework.data.domain.Page<ChannelAuthResolverEntity> findAll(org.springframework.data.domain.Pageable p) { throw nope(); }
        @Override public <S extends ChannelAuthResolverEntity> S save(S e) { byId.put(e.getId(), e); return e; }
        @Override public boolean existsById(Long id) { return byId.containsKey(id); }
        @Override public long count() { return byId.size(); }
        @Override public void deleteById(Long id) { byId.remove(id); }
        @Override public void delete(ChannelAuthResolverEntity e) { byId.remove(e.getId()); }
        @Override public void deleteAllById(Iterable<? extends Long> ids) { ids.forEach(byId::remove); }
        @Override public void deleteAll(Iterable<? extends ChannelAuthResolverEntity> es) { throw nope(); }
        @Override public void deleteAll() { byId.clear(); }
        @Override public <S extends ChannelAuthResolverEntity> Optional<S> findOne(org.springframework.data.domain.Example<S> e) { throw nope(); }
        @Override public <S extends ChannelAuthResolverEntity> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> e, org.springframework.data.domain.Pageable p) { throw nope(); }
        @Override public <S extends ChannelAuthResolverEntity> long count(org.springframework.data.domain.Example<S> e) { throw nope(); }
        @Override public <S extends ChannelAuthResolverEntity> boolean exists(org.springframework.data.domain.Example<S> e) { throw nope(); }
        @Override public <S extends ChannelAuthResolverEntity, R> R findBy(
                org.springframework.data.domain.Example<S> e,
                Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> fn) {
            throw nope();
        }
        private static RuntimeException nope() {
            return new UnsupportedOperationException("StubResolverRepo: method not stubbed");
        }
    }
}
