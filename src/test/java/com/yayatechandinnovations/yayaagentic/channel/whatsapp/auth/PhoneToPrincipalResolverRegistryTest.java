package com.yayatechandinnovations.yayaagentic.channel.whatsapp.auth;

import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhoneToPrincipalResolverRegistryTest {

    private static final Ids.TenantId TENANT = new Ids.TenantId("acme");
    private static final SenderId     SENDER = new SenderId("snd_acme_main");
    private static final ResolutionContext CTX =
            new ResolutionContext(Instant.now(), Optional.empty(), Optional.empty(), Map.of());

    @Test
    void rejects_blank_resolver_id_at_construction() {
        assertThatThrownBy(() -> new PhoneToPrincipalResolverRegistry(
                List.of(new FakeResolver(null, (t, s, p, c) -> Optional.empty())),
                Duration.ofMinutes(5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blank id()");
    }

    @Test
    void rejects_duplicate_resolver_ids_at_construction() {
        var a = new FakeResolver("dup", (t, s, p, c) -> Optional.empty());
        var b = new FakeResolver("dup", (t, s, p, c) -> Optional.empty());
        assertThatThrownBy(() -> new PhoneToPrincipalResolverRegistry(
                List.of(a, b), Duration.ofMinutes(5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dup");
    }

    @Test
    void unknown_resolver_id_throws_unknown_resolver_exception() {
        var reg = new PhoneToPrincipalResolverRegistry(List.of(), Duration.ofMinutes(5));
        assertThatThrownBy(() ->
                reg.resolve("nonexistent", TENANT, SENDER, "+15551234567", CTX))
                .isInstanceOf(PhoneToPrincipalResolverRegistry.UnknownResolverException.class)
                .hasMessageContaining("nonexistent");
    }

    @Test
    void caches_lookup_result_within_ttl() {
        var calls = new AtomicInteger();
        var r = new FakeResolver("cached", (t, s, p, c) -> {
            calls.incrementAndGet();
            return Optional.of(principal("u_42", Set.of("order:read")));
        });
        var reg = new PhoneToPrincipalResolverRegistry(List.of(r), Duration.ofMinutes(5));

        var first  = reg.resolve("cached", TENANT, SENDER, "+15551234567", CTX);
        var second = reg.resolve("cached", TENANT, SENDER, "+15551234567", CTX);

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        assertThat(calls.get()).isEqualTo(1);  // second call served from cache
    }

    @Test
    void cache_is_keyed_by_tenant_sender_and_phone() {
        var calls = new AtomicInteger();
        var r = new FakeResolver("multi", (t, s, p, c) -> {
            calls.incrementAndGet();
            return Optional.of(principal("u_" + p, Set.of()));
        });
        var reg = new PhoneToPrincipalResolverRegistry(List.of(r), Duration.ofMinutes(5));

        reg.resolve("multi", TENANT, SENDER, "+15550000001", CTX);
        reg.resolve("multi", TENANT, SENDER, "+15550000002", CTX);
        reg.resolve("multi", new Ids.TenantId("beta"), SENDER, "+15550000001", CTX);

        assertThat(calls.get()).isEqualTo(3);  // each tuple miss → resolver call
    }

    @Test
    void invalidate_all_drops_cache() {
        var calls = new AtomicInteger();
        var r = new FakeResolver("inv", (t, s, p, c) -> {
            calls.incrementAndGet();
            return Optional.of(principal("u_x", Set.of()));
        });
        var reg = new PhoneToPrincipalResolverRegistry(List.of(r), Duration.ofMinutes(5));

        reg.resolve("inv", TENANT, SENDER, "+15551234567", CTX);
        reg.invalidateAll();
        reg.resolve("inv", TENANT, SENDER, "+15551234567", CTX);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void rejects_resolver_output_carrying_reserved_channel_claims() {
        var leaky = new FakeResolver("leaky", (t, s, p, c) -> Optional.of(new Principal(
                "u_evil",
                t,
                Set.of(),
                Map.of("phone", "+15559999999"),   // reserved key — must be rejected
                Instant.now())));
        var reg = new PhoneToPrincipalResolverRegistry(List.of(leaky), Duration.ofMinutes(5));

        assertThatThrownBy(() -> reg.resolve("leaky", TENANT, SENDER, "+15551234567", CTX))
                .isInstanceOf(PhoneToPrincipalResolverRegistry.InvalidResolutionException.class)
                .hasMessageContaining("leaky")
                .hasMessageContaining("phone");
    }

    @Test
    void all_returns_registered_resolvers_for_admin_listing() {
        var a = new FakeResolver("a", (t, s, p, c) -> Optional.empty());
        var b = new FakeResolver("b", (t, s, p, c) -> Optional.empty());
        var reg = new PhoneToPrincipalResolverRegistry(List.of(a, b), Duration.ofMinutes(5));
        assertThat(reg.all()).hasSize(2);
        assertThat(reg.all()).extracting(PhoneToPrincipalResolver::id)
                .containsExactlyInAnyOrder("a", "b");
    }

    private static Principal principal(String subject, Set<String> scopes) {
        return new Principal(subject, TENANT, Set.copyOf(scopes), Map.of(), Instant.now());
    }

    private static class FakeResolver implements PhoneToPrincipalResolver {
        @FunctionalInterface
        interface Fn {
            Optional<Principal> apply(Ids.TenantId t, SenderId s, String p, ResolutionContext c);
        }
        private final String id;
        private final Fn fn;
        FakeResolver(String id, Fn fn) { this.id = id; this.fn = fn; }
        @Override public String id() { return id; }
        @Override public Optional<Principal> resolve(Ids.TenantId t, SenderId s,
                                                     String p, ResolutionContext c) {
            return fn.apply(t, s, p, c);
        }
    }
}
