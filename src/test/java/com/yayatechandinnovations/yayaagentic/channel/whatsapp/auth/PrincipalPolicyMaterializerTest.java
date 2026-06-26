package com.yayatechandinnovations.yayaagentic.channel.whatsapp.auth;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.auth.AuthenticationException;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrincipalPolicyMaterializerTest {

    private final PhoneToPrincipalResolverRegistry registry =
            new PhoneToPrincipalResolverRegistry(List.of(), Duration.ofMinutes(5));
    private final PrincipalPolicyMaterializer materializer =
            new PrincipalPolicyMaterializer(registry);

    private static AuthContext ctxWith(Map<String, String> headers) {
        return new AuthContext(new Ids.TenantId("acme"), headers, Optional.empty());
    }

    @Test
    void anonymous_produces_minimal_scopes_and_reserved_claims_only() throws AuthenticationException {
        Principal p = materializer.materialize(
                new DefaultPrincipalPolicy.Anonymous(),
                "+15551234567",
                "snd_acme_main",
                ctxWith(Map.of(
                        "x-channel-locale", "es-DO",
                        "x-channel-twilio-contact-sid", "MGabc123")));
        assertThat(p.subject()).isEqualTo("whatsapp:+15551234567");
        assertThat(p.scopes()).containsExactlyInAnyOrder("channel:whatsapp", "channel:read");
        assertThat(p.claims()).containsOnlyKeys(
                "phone", "channel.sender", "channel.kind", "locale", "twilioContactSid");
        assertThat(p.claims()).containsEntry("phone", "+15551234567");
        assertThat(p.claims()).containsEntry("channel.kind", "whatsapp");
    }

    @Test
    void anonymous_drops_locale_when_header_absent() throws AuthenticationException {
        Principal p = materializer.materialize(
                new DefaultPrincipalPolicy.Anonymous(),
                "+15551234567",
                "snd_x",
                ctxWith(Map.of()));
        assertThat(p.claims()).doesNotContainKey("locale");
        assertThat(p.claims()).doesNotContainKey("twilioContactSid");
    }

    @Test
    void phone_as_principal_merges_extra_scopes_above_baseline() throws AuthenticationException {
        Principal p = materializer.materialize(
                new DefaultPrincipalPolicy.PhoneAsPrincipal(
                        Set.of("order:read", "shipment:read"),
                        Map.of("role", "guest", "tier", "starter")),
                "+15551234567",
                "snd_x",
                ctxWith(Map.of("x-channel-locale", "en-US")));
        assertThat(p.scopes()).containsExactlyInAnyOrder(
                "channel:whatsapp", "channel:read", "order:read", "shipment:read");
        assertThat(p.claims()).containsEntry("role", "guest");
        assertThat(p.claims()).containsEntry("tier", "starter");
        // channel stamps still present
        assertThat(p.claims()).containsEntry("phone", "+15551234567");
        assertThat(p.claims()).containsEntry("channel.sender", "snd_x");
        assertThat(p.claims()).containsEntry("locale", "en-US");
    }

    @Test
    void phone_as_principal_extra_scopes_de_duplicate_against_baseline() throws AuthenticationException {
        Principal p = materializer.materialize(
                new DefaultPrincipalPolicy.PhoneAsPrincipal(
                        Set.of("channel:whatsapp", "order:read"),
                        Map.of()),
                "+15551234567",
                "snd_x",
                ctxWith(Map.of()));
        // Set semantics already de-dup; just confirm the baseline isn't doubled
        // and the extra scope landed
        assertThat(p.scopes()).containsExactlyInAnyOrder(
                "channel:whatsapp", "channel:read", "order:read");
    }

    @Test
    void phone_as_principal_rejects_reserved_claim_collision() {
        DefaultPrincipalPolicy bad = new DefaultPrincipalPolicy.PhoneAsPrincipal(
                Set.of(),
                Map.of("phone", "+15550009999"));   // attempt to override the channel stamp
        assertThatThrownBy(() -> materializer.materialize(
                bad, "+15551234567", "snd_x", ctxWith(Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved channel keys")
                .hasMessageContaining("phone");
    }

    @Test
    void phone_as_principal_rejects_channel_sender_collision() {
        DefaultPrincipalPolicy bad = new DefaultPrincipalPolicy.PhoneAsPrincipal(
                Set.of(),
                Map.of("channel.sender", "snd_evil"));
        assertThatThrownBy(() -> materializer.materialize(
                bad, "+15551234567", "snd_x", ctxWith(Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel.sender");
    }

    @Test
    void lookup_principal_unknown_resolver_throws_illegal_state() {
        DefaultPrincipalPolicy l = new DefaultPrincipalPolicy.LookupPrincipal("nonexistent", false);
        // Empty registry → operator misconfiguration → IllegalStateException
        // (loud, not a tenant-fault auth denial).
        assertThatThrownBy(() -> materializer.materialize(
                l, "+15551234567", "snd_x", ctxWith(Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nonexistent");
    }

    @Test
    void lookup_principal_happy_path_merges_resolver_scopes_and_claims()
            throws AuthenticationException {
        var resolver = new StubResolver("acme-resolver", (tenant, sender, phone, ctx) ->
                Optional.of(new Principal(
                        "u_42",
                        new Ids.TenantId("acme"),
                        Set.of("order:read"),
                        Map.of("role", "customer", "tier", "gold"),
                        Instant.now())));
        var registry = new PhoneToPrincipalResolverRegistry(
                List.of(resolver), Duration.ofMinutes(5));
        var materializer = new PrincipalPolicyMaterializer(registry);

        Principal p = materializer.materialize(
                new DefaultPrincipalPolicy.LookupPrincipal("acme-resolver", true),
                "+15551234567",
                "snd_x",
                ctxWith(Map.of("x-channel-locale", "en-US")));

        assertThat(p.subject()).isEqualTo("u_42");
        assertThat(p.scopes()).containsExactlyInAnyOrder(
                "channel:whatsapp", "channel:read", "order:read");
        assertThat(p.claims()).containsEntry("role", "customer");
        assertThat(p.claims()).containsEntry("tier", "gold");
        // channel stamps still present, NOT overridden by resolver
        assertThat(p.claims()).containsEntry("phone", "+15551234567");
        assertThat(p.claims()).containsEntry("channel.sender", "snd_x");
        assertThat(p.claims()).containsEntry("locale", "en-US");
    }

    @Test
    void lookup_principal_deny_on_unknown_true_raises_authentication_exception() {
        var resolver = new StubResolver("acme-resolver",
                (tenant, sender, phone, ctx) -> Optional.empty());
        var registry = new PhoneToPrincipalResolverRegistry(
                List.of(resolver), Duration.ofMinutes(5));
        var materializer = new PrincipalPolicyMaterializer(registry);

        assertThatThrownBy(() -> materializer.materialize(
                new DefaultPrincipalPolicy.LookupPrincipal("acme-resolver", true),
                "+15551234567",
                "snd_x",
                ctxWith(Map.of())))
                .isInstanceOf(AuthenticationException.class)
                .hasMessageContaining("denyOnUnknown=true");
    }

    @Test
    void lookup_principal_deny_on_unknown_false_falls_back_to_anonymous()
            throws AuthenticationException {
        var resolver = new StubResolver("acme-resolver",
                (tenant, sender, phone, ctx) -> Optional.empty());
        var registry = new PhoneToPrincipalResolverRegistry(
                List.of(resolver), Duration.ofMinutes(5));
        var materializer = new PrincipalPolicyMaterializer(registry);

        Principal p = materializer.materialize(
                new DefaultPrincipalPolicy.LookupPrincipal("acme-resolver", false),
                "+15551234567",
                "snd_x",
                ctxWith(Map.of()));

        assertThat(p.subject()).isEqualTo("whatsapp:+15551234567");
        assertThat(p.scopes()).containsExactlyInAnyOrder("channel:whatsapp", "channel:read");
    }

    @Test
    void lookup_principal_rejects_resolver_that_leaks_reserved_claims() {
        var resolver = new StubResolver("evil",
                (tenant, sender, phone, ctx) -> Optional.of(new Principal(
                        "u_evil",
                        new Ids.TenantId("acme"),
                        Set.of(),
                        // tries to override the channel.kind claim
                        Map.of("channel.kind", "spoofed-sms"),
                        Instant.now())));
        var registry = new PhoneToPrincipalResolverRegistry(
                List.of(resolver), Duration.ofMinutes(5));
        var materializer = new PrincipalPolicyMaterializer(registry);

        assertThatThrownBy(() -> materializer.materialize(
                new DefaultPrincipalPolicy.LookupPrincipal("evil", true),
                "+15551234567",
                "snd_x",
                ctxWith(Map.of())))
                .isInstanceOf(PhoneToPrincipalResolverRegistry.InvalidResolutionException.class)
                .hasMessageContaining("channel.kind");
    }

    /** Lightweight stand-in for tests — Mockito's inline mock-maker can't
     *  subclass interfaces with default methods on JDK 25 reliably either. */
    private static class StubResolver implements PhoneToPrincipalResolver {
        @FunctionalInterface
        interface ResolveFn {
            Optional<Principal> apply(Ids.TenantId tenant, SenderId sender,
                                      String phone, ResolutionContext ctx);
        }
        private final String id;
        private final ResolveFn fn;
        StubResolver(String id, ResolveFn fn) { this.id = id; this.fn = fn; }
        @Override public String id() { return id; }
        @Override public String description() { return "stub for tests"; }
        @Override public Optional<Principal> resolve(Ids.TenantId t, SenderId s,
                                                     String p, ResolutionContext c) {
            return fn.apply(t, s, p, c);
        }
    }
}
