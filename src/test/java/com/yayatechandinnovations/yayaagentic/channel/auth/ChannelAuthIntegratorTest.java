package com.yayatechandinnovations.yayaagentic.channel.auth;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.auth.Authenticator;
import com.yayatechandinnovations.yayaagentic.auth.PreVerifiedPrincipalAuthenticator;
import com.yayatechandinnovations.yayaagentic.auth.chain.AuthenticatorChain;
import com.yayatechandinnovations.yayaagentic.auth.dev.NoopAuthenticator;
import com.yayatechandinnovations.yayaagentic.channel.ChannelEndpointRef;
import com.yayatechandinnovations.yayaagentic.channel.auth.cache.AuthResolverDispatcher;
import com.yayatechandinnovations.yayaagentic.channel.binding.BindingId;
import com.yayatechandinnovations.yayaagentic.channel.binding.BindingStatus;
import com.yayatechandinnovations.yayaagentic.channel.binding.ChannelBinding;
import com.yayatechandinnovations.yayaagentic.channel.binding.OnUnresolvedPolicy;
import com.yayatechandinnovations.yayaagentic.channel.binding.RoutingRule;
import com.yayatechandinnovations.yayaagentic.channel.binding.SessionPolicy;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppPhoneAuthenticator;
import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelAuthIntegratorTest {

    @Test
    void faq_mode_binding_returns_anonymous_without_calling_dispatcher() {
        boolean[] dispatched = {false};
        AuthResolverDispatcher neverDispatch = stubDispatcher(
                (rid, reason) -> { dispatched[0] = true;
                        return new AuthResolverDispatcher.Result.Failed("nope", "should not run"); });
        ChannelAuthIntegrator integrator = new ChannelAuthIntegrator(
                neverDispatch, chainWithJustWhatsApp());

        ChannelAuthIntegrator.Resolved r = integrator.resolve(
                binding(null, OnUnresolvedPolicy.REJECT),
                baseCtx(),
                new Ids.SessionId("sess_x"),
                new SenderId("snd_main"),
                "+15551112222",
                "+15551234567");

        assertThat(r.decision()).isEqualTo(ChannelAuthIntegrator.Resolved.Decision.ANONYMOUS);
        assertThat(r.jwt()).isEmpty();
        assertThat(dispatched[0]).isFalse();
    }

    @Test
    void resolver_returns_jwt_which_yields_verified_principal_with_channel_claims_stamped() {
        // Pre-verified path: we hand the chain a Principal via alreadyVerified
        // and rely on PreVerifiedPrincipalAuthenticator to short-circuit.
        Principal tenantSide = new Principal(
                "u_42",
                new Ids.TenantId("acme"),
                Set.of("order:read", "shipment:read"),
                Map.of("role", "vip"),
                Instant.now());

        AuthResolverDispatcher dispatcher = stubDispatcher(
                (rid, reason) -> new AuthResolverDispatcher.Result.TokenIssued(
                        "eyJ.fake.jwt", AuthResolverDispatcher.CacheState.MISS));

        AuthenticatorChain chain = chainThatAlwaysReturns(tenantSide);
        ChannelAuthIntegrator integrator = new ChannelAuthIntegrator(dispatcher, chain);

        ChannelAuthIntegrator.Resolved r = integrator.resolve(
                binding(99L, OnUnresolvedPolicy.REJECT),
                baseCtx(),
                new Ids.SessionId("sess_x"),
                new SenderId("snd_main"),
                "+15551112222",
                "+15551234567");

        assertThat(r.decision()).isEqualTo(ChannelAuthIntegrator.Resolved.Decision.JWT_VERIFIED);
        assertThat(r.jwt()).contains("eyJ.fake.jwt");
        Principal p = r.principal().orElseThrow();
        // Tenant-side subject + claims preserved.
        assertThat(p.subject()).isEqualTo("u_42");
        assertThat(p.claims()).containsEntry("role", "vip");
        // Channel-reserved claims stamped on top.
        assertThat(p.claims()).containsEntry("phone", "+15551234567");
        assertThat(p.claims()).containsEntry("channel.sender", "snd_main");
        assertThat(p.claims()).containsEntry("channel.kind", "whatsapp");
        // Scopes unioned with baseline.
        assertThat(p.scopes()).contains("channel:whatsapp", "channel:read",
                "order:read", "shipment:read");
    }

    @Test
    void unresolved_with_anonymous_policy_returns_baseline_path() {
        AuthResolverDispatcher dispatcher = stubDispatcher(
                (rid, reason) -> new AuthResolverDispatcher.Result.Unresolved());

        ChannelAuthIntegrator integrator = new ChannelAuthIntegrator(
                dispatcher, chainWithJustWhatsApp());

        ChannelAuthIntegrator.Resolved r = integrator.resolve(
                binding(99L, OnUnresolvedPolicy.ANONYMOUS),
                baseCtx(),
                new Ids.SessionId("sess_x"),
                new SenderId("snd_main"),
                "+15551112222",
                "+15551234567");

        assertThat(r.decision()).isEqualTo(ChannelAuthIntegrator.Resolved.Decision.ANONYMOUS);
        assertThat(r.jwt()).isEmpty();
    }

    @Test
    void unresolved_with_reject_policy_returns_REJECT() {
        AuthResolverDispatcher dispatcher = stubDispatcher(
                (rid, reason) -> new AuthResolverDispatcher.Result.Unresolved());

        ChannelAuthIntegrator integrator = new ChannelAuthIntegrator(
                dispatcher, chainWithJustWhatsApp());

        ChannelAuthIntegrator.Resolved r = integrator.resolve(
                binding(99L, OnUnresolvedPolicy.REJECT),
                baseCtx(),
                new Ids.SessionId("sess_x"),
                new SenderId("snd_main"),
                "+15551112222",
                "+15551234567");

        assertThat(r.decision()).isEqualTo(ChannelAuthIntegrator.Resolved.Decision.REJECT);
        assertThat(r.principal()).isEmpty();
        assertThat(r.jwt()).isEmpty();
    }

    @Test
    void resolver_transport_failure_applies_on_unresolved_policy() {
        AuthResolverDispatcher dispatcher = stubDispatcher(
                (rid, reason) -> new AuthResolverDispatcher.Result.Failed(
                        "timeout", "resolver timed out"));

        ChannelAuthIntegrator integrator = new ChannelAuthIntegrator(
                dispatcher, chainWithJustWhatsApp());

        // ANONYMOUS policy → baseline path.
        var r1 = integrator.resolve(
                binding(99L, OnUnresolvedPolicy.ANONYMOUS),
                baseCtx(), new Ids.SessionId("sess_x"),
                new SenderId("snd_main"), "+15551112222", "+15551234567");
        assertThat(r1.decision()).isEqualTo(ChannelAuthIntegrator.Resolved.Decision.ANONYMOUS);

        // REJECT policy → drop.
        var r2 = integrator.resolve(
                binding(99L, OnUnresolvedPolicy.REJECT),
                baseCtx(), new Ids.SessionId("sess_x"),
                new SenderId("snd_main"), "+15551112222", "+15551234567");
        assertThat(r2.decision()).isEqualTo(ChannelAuthIntegrator.Resolved.Decision.REJECT);
    }

    @Test
    void chain_returns_empty_for_jwt_falls_back_to_on_unresolved() {
        // Resolver returned a token but no authenticator accepted it
        // (e.g. tenant_jwt_secrets row missing) → JWT_VERIFIED path fails
        // and the binding's on_unresolved decides.
        AuthResolverDispatcher dispatcher = stubDispatcher(
                (rid, reason) -> new AuthResolverDispatcher.Result.TokenIssued(
                        "eyJ.fake.jwt", AuthResolverDispatcher.CacheState.MISS));

        AuthenticatorChain emptyChain = chainThatReturnsEmpty();

        ChannelAuthIntegrator integrator = new ChannelAuthIntegrator(dispatcher, emptyChain);

        var r = integrator.resolve(
                binding(99L, OnUnresolvedPolicy.REJECT),
                baseCtx(), new Ids.SessionId("sess_x"),
                new SenderId("snd_main"), "+15551112222", "+15551234567");
        assertThat(r.decision()).isEqualTo(ChannelAuthIntegrator.Resolved.Decision.REJECT);
    }

    @Test
    void resolved_authContextForEngine_for_verified_stamps_principal_into_alreadyVerified() {
        Principal stamped = new Principal("u_42", new Ids.TenantId("acme"),
                Set.of("channel:whatsapp"), Map.of(), Instant.now());
        ChannelAuthIntegrator.Resolved r = new ChannelAuthIntegrator.Resolved(
                ChannelAuthIntegrator.Resolved.Decision.JWT_VERIFIED,
                Optional.of(stamped), Optional.of("jwt"));

        AuthContext engineCtx = r.authContextForEngine(baseCtx());

        assertThat(engineCtx.alreadyVerified()).contains(stamped);
        // Headers preserved so step-up + channel claims still derivable later.
        assertThat(engineCtx.headers())
                .containsEntry(WhatsAppPhoneAuthenticator.HEADER_END_USER, "+15551234567");
    }

    // ---- fixtures -----------------------------------------------------

    private static AuthContext baseCtx() {
        return new AuthContext(
                new Ids.TenantId("acme"),
                Map.of(
                        WhatsAppPhoneAuthenticator.HEADER_END_USER, "+15551234567",
                        WhatsAppPhoneAuthenticator.HEADER_SENDER_ID, "snd_main",
                        WhatsAppPhoneAuthenticator.HEADER_LOCALE, "es-DO"),
                Optional.empty());
    }

    private static ChannelBinding binding(Long resolverId, OnUnresolvedPolicy onUnresolved) {
        return new ChannelBinding(
                new BindingId(1L),
                new Ids.TenantId("acme"),
                new ChannelEndpointRef.WhatsAppSenderRef(new SenderId("snd_main")),
                new Ids.ProfileId("p", 1),
                Optional.empty(),
                new RoutingRule.MatchAny(),
                SessionPolicy.defaults(),
                100, BindingStatus.ACTIVE,
                Instant.now(), null,
                resolverId, onUnresolved);
    }

    private static AuthResolverDispatcher stubDispatcher(
            BiFunction<Long, com.yayatechandinnovations.yayaagentic.channel.auth.AuthResolver.ResolveReason,
                    AuthResolverDispatcher.Result> fn) {
        return new AuthResolverDispatcher(null, null, null, null, null) {
            @Override
            public Result dispatch(long resolverId, Ids.TenantId tenant, SenderId sender,
                                   String senderPhone, String callerPhone,
                                   Ids.SessionId session, String channel,
                                   com.yayatechandinnovations.yayaagentic.channel.auth.AuthResolver.ResolveReason reason) {
                return fn.apply(resolverId, reason);
            }
            @Override public void evict(Ids.TenantId t, SenderId s, String c) {}
            @Override public long evictAllForTenant(Ids.TenantId t) { return 0; }
        };
    }

    /** Chain that always pre-verified-authenticates whatever's in alreadyVerified.
     *  When alreadyVerified is empty, returns empty.
     *  We use it for the "verified Principal stamping" tests — the
     *  ChannelAuthIntegrator builds the JWT AuthContext with our fake jwt,
     *  but no real authenticator can verify it, so we cheat: PreVerified
     *  short-circuits when we inject the tenant-side principal pre-emptively. */
    private static AuthenticatorChain chainThatAlwaysReturns(Principal p) {
        Authenticator alwaysReturns = new Authenticator() {
            @Override public String name() { return "always-returns"; }
            @Override public Optional<Principal> tryAuthenticate(AuthContext ctx) {
                return Optional.of(p);
            }
        };
        return buildChain(List.of(alwaysReturns));
    }

    private static AuthenticatorChain chainWithJustWhatsApp() {
        return buildChain(List.of(new WhatsAppPhoneAuthenticator()));
    }

    private static AuthenticatorChain chainThatReturnsEmpty() {
        Authenticator alwaysEmpty = new Authenticator() {
            @Override public String name() { return "never-wins"; }
            @Override public Optional<Principal> tryAuthenticate(AuthContext ctx) {
                return Optional.empty();
            }
        };
        return buildChain(List.of(alwaysEmpty));
    }

    private static AuthenticatorChain buildChain(List<Authenticator> candidates) {
        YayaAgenticProperties props = new YayaAgenticProperties(
                "default", "default", null, null, null, null,
                new YayaAgenticProperties.Auth(false, null, null, null),
                null, null, null, null);
        NoopAuthenticator noop = new NoopAuthenticator();
        java.util.List<Authenticator> all = new java.util.ArrayList<>(candidates);
        all.add(new PreVerifiedPrincipalAuthenticator());
        return new AuthenticatorChain(all, noop, props);
    }
}
