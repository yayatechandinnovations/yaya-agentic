package com.yayatechandinnovations.yayaagentic.channel.chat;

import com.yayatechandinnovations.yayaagentic.api.ChatApiException;
import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.auth.Authenticator;
import com.yayatechandinnovations.yayaagentic.auth.PreVerifiedPrincipalAuthenticator;
import com.yayatechandinnovations.yayaagentic.auth.anon.AnonymousAuthenticator;
import com.yayatechandinnovations.yayaagentic.auth.anon.VerifiedDeviceAuthenticator;
import com.yayatechandinnovations.yayaagentic.auth.chain.AuthenticatorChain;
import com.yayatechandinnovations.yayaagentic.auth.dev.NoopAuthenticator;
import com.yayatechandinnovations.yayaagentic.channel.auth.AuthResolver;
import com.yayatechandinnovations.yayaagentic.channel.auth.ChannelAuthIntegrator;
import com.yayatechandinnovations.yayaagentic.channel.auth.ChannelAuthTrace;
import com.yayatechandinnovations.yayaagentic.channel.auth.cache.AuthResolverDispatcher;
import com.yayatechandinnovations.yayaagentic.channel.auth.cache.AuthResolverTokenCache;
import com.yayatechandinnovations.yayaagentic.channel.binding.SessionPolicy;
import com.yayatechandinnovations.yayaagentic.channel.chat.verify.VerifiedDevice;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppPhoneAuthenticator;
import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.profile.Profile;
import com.yayatechandinnovations.yayaagentic.profile.ProfileRegistry;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Locks in the shared chat identity policy — the code the public front door and
 * the operator chat playground both run through. The security-critical property
 * under test: an unresolved resolver-exchange under AUTH_REQUIRED yields a
 * {@code REJECT} returned as data (never a JWT), and the trace it records is
 * keyed so the inspector can find the failing exchange.
 */
class ChatAuthServiceTest {

    private static final String PHONE = "+15551234567";

    @Test
    void anonymous_entry_point_resolves_anonymous_without_calling_the_resolver() {
        boolean[] dispatched = {false};
        ChatAuthService svc = service(() -> { dispatched[0] = true;
                return new AuthResolverDispatcher.Result.Unresolved(); });

        ChatAuthService.Resolution r = svc.resolveForStart(
                ep(AuthMode.ANONYMOUS_ONLY, new AuthConfig.None(), false),
                "e_abc", "dev1", ChatAuthService.Caller.anonymous());

        assertThat(r.decision()).isEqualTo(ChatAuthService.Decision.ANONYMOUS);
        assertThat(r.auth()).isNotNull();
        assertThat(r.jwt()).isEmpty();
        assertThat(r.callerSha256()).isNull();
        assertThat(dispatched[0]).isFalse();
    }

    @Test
    void auth_required_without_bearer_throws_auth_required() {
        ChatAuthService svc = service(AuthResolverDispatcher.Result.Unresolved::new);

        assertThatThrownBy(() -> svc.resolveForStart(
                ep(AuthMode.AUTH_REQUIRED, new AuthConfig.None(), false),
                "e_abc", "dev1", ChatAuthService.Caller.anonymous()))
                .isInstanceOf(ChatApiException.class)
                .satisfies(e -> assertThat(((ChatApiException) e).code()).isEqualTo("auth_required"));
    }

    @Test
    void resolver_exchange_without_verified_device_throws_verification_required() {
        ChatAuthService svc = service(AuthResolverDispatcher.Result.Unresolved::new);

        assertThatThrownBy(() -> svc.resolveForStart(
                ep(AuthMode.AUTH_REQUIRED, new AuthConfig.ResolverExchange(99L, "F0DX-12FF"), true),
                "e_abc", "dev1", ChatAuthService.Caller.anonymous()))
                .isInstanceOf(ChatApiException.class)
                .satisfies(e -> assertThat(((ChatApiException) e).code()).isEqualTo("verification_required"));
    }

    @Test
    void resolver_unresolved_under_auth_required_returns_reject_as_data_with_caller_hash() {
        ChatAuthService svc = service(AuthResolverDispatcher.Result.Unresolved::new);

        ChatAuthService.Resolution r = svc.resolveForStart(
                ep(AuthMode.AUTH_REQUIRED, new AuthConfig.ResolverExchange(99L, "F0DX-12FF"), true),
                "e_abc", "dev1", ChatAuthService.Caller.device(new VerifiedDevice(PHONE, "Tester", "dev1")));

        assertThat(r.rejected()).isTrue();
        assertThat(r.auth()).isNull();          // fail closed — no principal to run under
        assertThat(r.jwt()).isEmpty();           // never a token on reject
        assertThat(r.verificationDecision()).isEqualTo("REJECT");
        // Keyed exactly as HttpAuthResolver records the exchange, so the
        // inspector can locate the failing request/response.
        assertThat(r.callerSha256()).isEqualTo(AuthResolverTokenCache.sha256LowerHex(PHONE));
    }

    @Test
    void resolver_unresolved_under_auth_optional_falls_back_to_verified_device() {
        ChatAuthService svc = service(AuthResolverDispatcher.Result.Unresolved::new);

        ChatAuthService.Resolution r = svc.resolveForStart(
                ep(AuthMode.AUTH_OPTIONAL, new AuthConfig.ResolverExchange(99L, "F0DX-12FF"), true),
                "e_abc", "dev1", ChatAuthService.Caller.device(new VerifiedDevice(PHONE, "Tester", "dev1")));

        assertThat(r.rejected()).isFalse();
        assertThat(r.decision()).isEqualTo(ChatAuthService.Decision.DEVICE);
        assertThat(r.auth()).isNotNull();
        assertThat(r.callerSha256()).isEqualTo(AuthResolverTokenCache.sha256LowerHex(PHONE));
    }

    @Test
    void record_trace_makes_the_session_inspectable_by_tenant_and_caller_hash() {
        ChannelAuthTrace trace = new ChannelAuthTrace();
        ChatAuthService svc = service(AuthResolverDispatcher.Result.Unresolved::new, trace);
        ChatEntryPoint ep = ep(AuthMode.AUTH_REQUIRED,
                new AuthConfig.ResolverExchange(99L, "F0DX-12FF"), true);

        ChatAuthService.Resolution r = svc.resolveForStart(
                ep, "e_abc", "dev1", ChatAuthService.Caller.device(new VerifiedDevice(PHONE, "Tester", "dev1")));
        Ids.SessionId sid = new Ids.SessionId("sess_reject_probe");
        svc.recordTrace(sid, ep, r, ChannelAuthTrace.TokenState.ABSENT);

        ChannelAuthTrace.Snapshot snap = trace.get(sid).orElseThrow();
        assertThat(snap.tenantId()).isEqualTo("acme");
        assertThat(snap.callerSha256()).isEqualTo(AuthResolverTokenCache.sha256LowerHex(PHONE));
        assertThat(snap.verificationDecision()).isEqualTo("REJECT");
        assertThat(snap.resolverId()).isEqualTo(99L);
    }

    /* ------------------------------ helpers ------------------------------- */

    private static ChatAuthService service(Supplier<AuthResolverDispatcher.Result> result) {
        return service(result, new ChannelAuthTrace());
    }

    private static ChatAuthService service(Supplier<AuthResolverDispatcher.Result> result,
                                           ChannelAuthTrace trace) {
        ChannelAuthIntegrator integrator = new ChannelAuthIntegrator(
                stubDispatcher(result), chainWithJustWhatsApp(), stubRegistry());
        return new ChatAuthService(integrator, new VerifiedDeviceAuthenticator(),
                new AnonymousAuthenticator(), trace);
    }

    private static ChatEntryPoint ep(AuthMode mode, AuthConfig cfg, boolean requiresPhone) {
        return new ChatEntryPoint(
                new ChatEntryPointId("chat_test"),
                new Ids.TenantId("acme"),
                "e_abc",
                new Ids.ProfileId("retail-customer", 1),
                new Branding("Shop", "https://logo", null, null, "en-US", null),
                mode, cfg,
                SessionPolicy.defaults(), AbusePolicy.defaults(),
                requiresPhone, false, EntryPointStatus.ACTIVE,
                Instant.now(), Instant.now());
    }

    private static ProfileRegistry stubRegistry() {
        return new ProfileRegistry() {
            @Override public Optional<Profile> find(Ids.TenantId t, Ids.ProfileId p) { return Optional.empty(); }
            @Override public Optional<Profile> findLatest(Ids.TenantId t, Ids.ProfileId p) { return Optional.empty(); }
            @Override public java.util.List<Profile> listFor(Ids.TenantId t) { return java.util.List.of(); }
            @Override public void register(Profile profile) {}
        };
    }

    private static AuthResolverDispatcher stubDispatcher(Supplier<AuthResolverDispatcher.Result> fn) {
        return new AuthResolverDispatcher(null, null, null, null, null) {
            @Override
            public Result dispatch(long resolverId, Ids.TenantId tenant, SenderId sender,
                                   String senderPhone, String senderKey, String callerPhone,
                                   Ids.SessionId session, String channel,
                                   AuthResolver.ResolveReason reason) {
                return fn.get();
            }
            @Override public void evict(Ids.TenantId t, SenderId s, String c) {}
            @Override public long evictAllForTenant(Ids.TenantId t) { return 0; }
        };
    }

    private static AuthenticatorChain chainWithJustWhatsApp() {
        YayaAgenticProperties props = new YayaAgenticProperties(
                "default", "default", null, null, null, null,
                new YayaAgenticProperties.Auth(false, null, null, null),
                null, null, null, null);
        List<Authenticator> all = new ArrayList<>(List.of(new WhatsAppPhoneAuthenticator()));
        all.add(new PreVerifiedPrincipalAuthenticator());
        return new AuthenticatorChain(all, new NoopAuthenticator(), props);
    }
}
