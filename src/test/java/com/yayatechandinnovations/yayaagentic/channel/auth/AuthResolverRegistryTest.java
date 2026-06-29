package com.yayatechandinnovations.yayaagentic.channel.auth;

import com.yayatechandinnovations.yayaagentic.persistence.ChannelAuthResolverEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChannelAuthResolverRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthResolverRegistryTest {

    private final HttpAuthResolverFactory neverFactory = new HttpAuthResolverFactory(
            null, null, null, null, null, null) {
        @Override
        public HttpAuthResolver build(ChannelAuthResolverEntity row) {
            throw new IllegalStateException(
                    "factory should not be called for BEAN rows");
        }
    };

    @Test
    void dispatches_bean_kind_to_named_bean() {
        StubBean stub = new StubBean("acme-customer-lookup");
        StubRepo repo = new StubRepo();
        repo.put(beanRow("acme", "acme-customer-lookup", "acme-customer-lookup"));
        AuthResolverRegistry reg = new AuthResolverRegistry(repo, neverFactory, List.of(stub));

        Optional<AuthResolver> r = reg.lookup("acme", "acme-customer-lookup");
        assertThat(r).isPresent();
        assertThat(r.get().name()).isEqualTo("acme-customer-lookup");
        assertThat(r.get()).isSameAs(stub);
    }

    @Test
    void bean_kind_with_unknown_bean_throws_config_error() {
        StubRepo repo = new StubRepo();
        repo.put(beanRow("acme", "missing", "not-on-classpath"));
        AuthResolverRegistry reg = new AuthResolverRegistry(repo, neverFactory, List.of());

        assertThatThrownBy(() -> reg.lookup("acme", "missing"))
                .isInstanceOf(AuthResolverException.class)
                .hasMessageContaining("not-on-classpath");
    }

    @Test
    void deactivated_rows_are_filtered_out() {
        ChannelAuthResolverEntity dead = beanRow("acme", "old", "acme-customer-lookup");
        dead.setStatus("DEACTIVATED");
        StubRepo repo = new StubRepo();
        repo.put(dead);
        AuthResolverRegistry reg = new AuthResolverRegistry(repo,
                neverFactory, List.of(new StubBean("acme-customer-lookup")));

        assertThat(reg.lookup("acme", "old")).isEmpty();
    }

    @Test
    void http_kind_delegates_to_factory() {
        AtomicInteger builds = new AtomicInteger();
        HttpAuthResolverFactory tracingFactory = new HttpAuthResolverFactory(
                null, null, null, null, null, null) {
            @Override
            public HttpAuthResolver build(ChannelAuthResolverEntity row) {
                builds.incrementAndGet();
                return null;
            }
        };
        StubRepo repo = new StubRepo();
        repo.put(httpRow("acme", "acme-http", "/yaya/resolve-identity"));
        AuthResolverRegistry reg = new AuthResolverRegistry(repo, tracingFactory, List.of());

        reg.lookup("acme", "acme-http");
        assertThat(builds.get()).isEqualTo(1);
    }

    @Test
    void duplicate_bean_names_fail_registry_construction() {
        StubBean a = new StubBean("dup");
        StubBean b = new StubBean("dup");
        StubRepo repo = new StubRepo();
        assertThatThrownBy(() ->
                new AuthResolverRegistry(repo, neverFactory, List.of(a, b)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Two AuthResolver beans claim name='dup'");
    }

    @Test
    void blank_bean_name_fails_registry_construction() {
        StubBean blank = new StubBean("");
        StubRepo repo = new StubRepo();
        assertThatThrownBy(() ->
                new AuthResolverRegistry(repo, neverFactory, List.of(blank)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blank name()");
    }

    @Test
    void registered_bean_names_lists_sorted() {
        AuthResolverRegistry reg = new AuthResolverRegistry(new StubRepo(), neverFactory,
                List.of(new StubBean("zeta"), new StubBean("alpha"), new StubBean("mike")));
        assertThat(reg.registeredBeanNames()).containsExactly("alpha", "mike", "zeta");
    }

    /** §13.7 — Bean lookup is exact-match; a row pointing at
     *  {@code acme-customer-lookup} does not accidentally resolve to
     *  {@code acme-customer-lookup-staging} just because the prefix overlaps. */
    @Test
    void bean_lookup_is_exact_match_with_overlapping_names() {
        StubBean primary = new StubBean("acme-customer-lookup");
        StubBean staging = new StubBean("acme-customer-lookup-staging");
        StubRepo repo = new StubRepo();
        repo.put(beanRow("acme", "rowName", "acme-customer-lookup"));
        AuthResolverRegistry reg = new AuthResolverRegistry(repo, neverFactory,
                List.of(primary, staging));

        AuthResolver resolved = reg.lookup("acme", "rowName").orElseThrow();
        assertThat(resolved).isSameAs(primary);
        assertThat(resolved).isNotSameAs(staging);
    }

    // ---- fixtures -----------------------------------------------------

    private static ChannelAuthResolverEntity beanRow(String tenant, String name, String beanName) {
        ChannelAuthResolverEntity e = new ChannelAuthResolverEntity(tenant, name, null, "BEAN");
        e.setBeanName(beanName);
        return e;
    }

    private static ChannelAuthResolverEntity httpRow(String tenant, String name, String path) {
        ChannelAuthResolverEntity e = new ChannelAuthResolverEntity(tenant, name, null, "HTTP");
        e.setHttpPath(path);
        return e;
    }

    private static class StubBean implements AuthResolver {
        private final String name;
        StubBean(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public Optional<String> resolve(ResolveRequest r) {
            return Optional.of("stub-token-for-" + name);
        }
    }

    /** Map-backed JpaRepository — covers only the methods AuthResolverRegistry uses. */
    private static class StubRepo implements ChannelAuthResolverRepository {
        final Map<String, ChannelAuthResolverEntity> byName = new HashMap<>();

        void put(ChannelAuthResolverEntity e) { byName.put(e.getName(), e); }

        @Override
        public Optional<ChannelAuthResolverEntity> findByTenantIdAndName(String t, String n) {
            ChannelAuthResolverEntity row = byName.get(n);
            if (row == null || !row.getTenantId().equals(t)) return Optional.empty();
            return Optional.of(row);
        }
        @Override public List<ChannelAuthResolverEntity> findByTenantIdOrderByNameAsc(String t) {
            return List.of();
        }
        @Override public List<ChannelAuthResolverEntity> findByTenantIdAndStatusOrderByNameAsc(
                String t, String s) {
            return List.of();
        }

        // ---- unused JpaRepository methods - throw to flag accidental use ----
        @Override public List<ChannelAuthResolverEntity> findAll() { throw nope(); }
        @Override public List<ChannelAuthResolverEntity> findAll(Sort sort) { throw nope(); }
        @Override public List<ChannelAuthResolverEntity> findAllById(Iterable<Long> ids) { throw nope(); }
        @Override public <S extends ChannelAuthResolverEntity> List<S> saveAll(Iterable<S> e) { throw nope(); }
        @Override public void flush() { throw nope(); }
        @Override public <S extends ChannelAuthResolverEntity> S saveAndFlush(S e) { throw nope(); }
        @Override public <S extends ChannelAuthResolverEntity> List<S> saveAllAndFlush(Iterable<S> e) { throw nope(); }
        @Override public void deleteAllInBatch(Iterable<ChannelAuthResolverEntity> es) { throw nope(); }
        @Override public void deleteAllByIdInBatch(Iterable<Long> ids) { throw nope(); }
        @Override public void deleteAllInBatch() { throw nope(); }
        @Override public ChannelAuthResolverEntity getOne(Long id) { throw nope(); }
        @Override public ChannelAuthResolverEntity getById(Long id) { throw nope(); }
        @Override public ChannelAuthResolverEntity getReferenceById(Long id) { throw nope(); }
        @Override public <S extends ChannelAuthResolverEntity> List<S> findAll(Example<S> e) { throw nope(); }
        @Override public <S extends ChannelAuthResolverEntity> List<S> findAll(Example<S> e, Sort s) { throw nope(); }
        @Override public Page<ChannelAuthResolverEntity> findAll(Pageable p) { throw nope(); }
        @Override public <S extends ChannelAuthResolverEntity> S save(S e) { throw nope(); }
        @Override public Optional<ChannelAuthResolverEntity> findById(Long id) { throw nope(); }
        @Override public boolean existsById(Long id) { throw nope(); }
        @Override public long count() { throw nope(); }
        @Override public void deleteById(Long id) { throw nope(); }
        @Override public void delete(ChannelAuthResolverEntity e) { throw nope(); }
        @Override public void deleteAllById(Iterable<? extends Long> ids) { throw nope(); }
        @Override public void deleteAll(Iterable<? extends ChannelAuthResolverEntity> es) { throw nope(); }
        @Override public void deleteAll() { throw nope(); }
        @Override public <S extends ChannelAuthResolverEntity> Optional<S> findOne(Example<S> e) { throw nope(); }
        @Override public <S extends ChannelAuthResolverEntity> Page<S> findAll(Example<S> e, Pageable p) { throw nope(); }
        @Override public <S extends ChannelAuthResolverEntity> long count(Example<S> e) { throw nope(); }
        @Override public <S extends ChannelAuthResolverEntity> boolean exists(Example<S> e) { throw nope(); }
        @Override public <S extends ChannelAuthResolverEntity, R> R findBy(
                Example<S> e, Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> fn) {
            throw nope();
        }

        private static RuntimeException nope() {
            return new UnsupportedOperationException("StubRepo: method not stubbed");
        }
    }
}
