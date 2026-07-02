package com.yayatechandinnovations.yayaagentic.channel.auth;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.auth.Authenticator;
import com.yayatechandinnovations.yayaagentic.auth.PreVerifiedPrincipalAuthenticator;
import com.yayatechandinnovations.yayaagentic.auth.chain.AuthenticatorChain;
import com.yayatechandinnovations.yayaagentic.auth.dev.NoopAuthenticator;
import com.yayatechandinnovations.yayaagentic.channel.auth.cache.AuthResolverDispatcher;
import com.yayatechandinnovations.yayaagentic.channel.binding.OnOverrideDenied;
import com.yayatechandinnovations.yayaagentic.channel.binding.OnUnresolvedPolicy;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.profile.Profile;
import com.yayatechandinnovations.yayaagentic.profile.ProfileRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.yayatechandinnovations.yayaagentic.channel.auth.ChannelAuthIntegrator.RESERVED_PROFILE_CLAIM;
import static com.yayatechandinnovations.yayaagentic.channel.auth.ChannelAuthIntegrator.RESERVED_PROFILE_VERSION_CLAIM;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guardrails + anti-smuggling for a resolver-selected profile override
 * ({@code resolver-profile-override-design.md} §6.3, §7).
 */
class ChannelAuthProfileOverrideTest {

    private static final Ids.TenantId TENANT = new Ids.TenantId("acme");

    @Test
    void applied_override_stamps_reserved_profile_claim() {
        Principal p = resolveWith(
                Optional.of(new AuthResolver.ProfileSelection("retail-vip", null)),
                Map.of("role", "vip"),
                Set.of("retail-vip"),
                ChannelAuthIntegrator.OverridePolicy.allowAny()).principal().orElseThrow();

        assertThat(p.claims()).containsEntry(RESERVED_PROFILE_CLAIM, "retail-vip");
        assertThat(p.claims()).doesNotContainKey(RESERVED_PROFILE_VERSION_CLAIM);
    }

    @Test
    void applied_override_with_pinned_version_stamps_version() {
        Principal p = resolveWith(
                Optional.of(new AuthResolver.ProfileSelection("retail-vip", 3)),
                Map.of(),
                Set.of("retail-vip"),
                ChannelAuthIntegrator.OverridePolicy.allowAny()).principal().orElseThrow();

        assertThat(p.claims()).containsEntry(RESERVED_PROFILE_CLAIM, "retail-vip");
        assertThat(p.claims()).containsEntry(RESERVED_PROFILE_VERSION_CLAIM, 3);
    }

    @Test
    void jwt_body_cannot_smuggle_the_reserved_profile_claim() {
        // The tenant-side JWT carries a forged yaya.profile; no structured
        // override is returned. The forged claim must be stripped, not honored.
        Principal p = resolveWith(
                Optional.empty(),
                Map.of(RESERVED_PROFILE_CLAIM, "merchant-admin"),
                Set.of("merchant-admin"),
                ChannelAuthIntegrator.OverridePolicy.allowAny()).principal().orElseThrow();

        assertThat(p.claims()).doesNotContainKey(RESERVED_PROFILE_CLAIM);
    }

    @Test
    void structured_override_wins_over_a_smuggled_jwt_claim() {
        Principal p = resolveWith(
                Optional.of(new AuthResolver.ProfileSelection("retail-vip", null)),
                Map.of(RESERVED_PROFILE_CLAIM, "merchant-admin"),
                Set.of("retail-vip", "merchant-admin"),
                ChannelAuthIntegrator.OverridePolicy.allowAny()).principal().orElseThrow();

        assertThat(p.claims()).containsEntry(RESERVED_PROFILE_CLAIM, "retail-vip");
    }

    @Test
    void unpublished_override_is_ignored_under_IGNORE() {
        ChannelAuthIntegrator.Resolved r = resolveWith(
                Optional.of(new AuthResolver.ProfileSelection("ghost", null)),
                Map.of(),
                Set.of("retail-vip"),                 // "ghost" not published
                ChannelAuthIntegrator.OverridePolicy.allowAny());

        assertThat(r.decision()).isEqualTo(ChannelAuthIntegrator.Resolved.Decision.JWT_VERIFIED);
        assertThat(r.principal().orElseThrow().claims()).doesNotContainKey(RESERVED_PROFILE_CLAIM);
    }

    @Test
    void not_allowlisted_override_is_ignored_under_IGNORE() {
        ChannelAuthIntegrator.Resolved r = resolveWith(
                Optional.of(new AuthResolver.ProfileSelection("retail-vip", null)),
                Map.of(),
                Set.of("retail-vip"),
                new ChannelAuthIntegrator.OverridePolicy(
                        Set.of("retail-standard"), OnOverrideDenied.IGNORE));

        assertThat(r.decision()).isEqualTo(ChannelAuthIntegrator.Resolved.Decision.JWT_VERIFIED);
        assertThat(r.principal().orElseThrow().claims()).doesNotContainKey(RESERVED_PROFILE_CLAIM);
    }

    @Test
    void not_allowlisted_override_rejects_the_inbound_under_REJECT() {
        ChannelAuthIntegrator.Resolved r = resolveWith(
                Optional.of(new AuthResolver.ProfileSelection("retail-vip", null)),
                Map.of(),
                Set.of("retail-vip"),
                new ChannelAuthIntegrator.OverridePolicy(
                        Set.of("retail-standard"), OnOverrideDenied.REJECT));

        assertThat(r.decision()).isEqualTo(ChannelAuthIntegrator.Resolved.Decision.REJECT);
        assertThat(r.principal()).isEmpty();
    }

    @Test
    void unpublished_override_rejects_the_inbound_under_REJECT() {
        ChannelAuthIntegrator.Resolved r = resolveWith(
                Optional.of(new AuthResolver.ProfileSelection("ghost", null)),
                Map.of(),
                Set.of("retail-vip"),
                new ChannelAuthIntegrator.OverridePolicy(
                        Set.of(), OnOverrideDenied.REJECT));

        assertThat(r.decision()).isEqualTo(ChannelAuthIntegrator.Resolved.Decision.REJECT);
    }

    // ---- harness ------------------------------------------------------

    private ChannelAuthIntegrator.Resolved resolveWith(
            Optional<AuthResolver.ProfileSelection> override,
            Map<String, Object> jwtClaims,
            Set<String> publishedKeys,
            ChannelAuthIntegrator.OverridePolicy policy) {

        Principal tenantSide = new Principal("u_42", TENANT,
                Set.of("order:read"), jwtClaims, Instant.now());
        AuthResolverDispatcher dispatcher = stubDispatcher(
                new AuthResolverDispatcher.Result.TokenIssued(
                        "eyJ.fake.jwt", override, AuthResolverDispatcher.CacheState.MISS));
        ChannelAuthIntegrator integrator = new ChannelAuthIntegrator(
                dispatcher, chainThatAlwaysReturns(tenantSide), registry(publishedKeys));

        return integrator.resolveFor(
                TENANT, 99L, OnUnresolvedPolicy.REJECT,
                new AuthContext(TENANT, Map.of(), Optional.empty()),
                null, new SenderId("snd_main"), "+15551112222", null, "+15551234567",
                "whatsapp", Set.of("channel:whatsapp"),
                new java.util.LinkedHashMap<>(Map.of("channel.kind", "whatsapp")),
                policy);
    }

    private static ProfileRegistry registry(Set<String> publishedKeys) {
        return new ProfileRegistry() {
            private Profile p(Ids.ProfileId id) {
                return new Profile(id, TENANT, "n", "g", "sys",
                        List.of(), List.of(), null, "en", Map.of());
            }
            @Override public Optional<Profile> find(Ids.TenantId t, Ids.ProfileId id) {
                return publishedKeys.contains(id.value()) ? Optional.of(p(id)) : Optional.empty();
            }
            @Override public Optional<Profile> findLatest(Ids.TenantId t, Ids.ProfileId id) {
                return publishedKeys.contains(id.value()) ? Optional.of(p(id)) : Optional.empty();
            }
            @Override public List<Profile> listFor(Ids.TenantId t) { return List.of(); }
            @Override public void register(Profile profile) {}
        };
    }

    private static AuthResolverDispatcher stubDispatcher(AuthResolverDispatcher.Result result) {
        return new AuthResolverDispatcher(null, null, null, null, null) {
            @Override
            public Result dispatch(long resolverId, Ids.TenantId tenant, SenderId sender,
                                   String senderPhone, String senderKey, String callerPhone,
                                   Ids.SessionId session, String channel,
                                   AuthResolver.ResolveReason reason) {
                return result;
            }
        };
    }

    private static AuthenticatorChain chainThatAlwaysReturns(Principal p) {
        Authenticator always = new Authenticator() {
            @Override public String name() { return "always-returns"; }
            @Override public Optional<Principal> tryAuthenticate(AuthContext ctx) { return Optional.of(p); }
        };
        YayaAgenticProperties props = new YayaAgenticProperties(
                "default", "default", null, null, null, null,
                new YayaAgenticProperties.Auth(false, null, null, null),
                null, null, null, null);
        List<Authenticator> all = new java.util.ArrayList<>(List.of(always));
        all.add(new PreVerifiedPrincipalAuthenticator());
        return new AuthenticatorChain(all, new NoopAuthenticator(), props);
    }
}
