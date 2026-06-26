package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.auth.AuthenticationException;
import com.yayatechandinnovations.yayaagentic.channel.binding.ChannelBindingService;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.auth.BindingPolicyResolver;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.auth.DefaultPrincipalPolicy;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.auth.PhoneToPrincipalResolverRegistry;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.auth.PrincipalPolicyMaterializer;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WhatsAppPhoneAuthenticatorTest {

    /** Lightweight stand-in for {@link BindingPolicyResolver}; Mockito's
     *  inline mock-maker can't subclass it cleanly on JDK 25. */
    private static class StubResolver extends BindingPolicyResolver {
        private DefaultPrincipalPolicy next = new DefaultPrincipalPolicy.Anonymous();
        final AtomicInteger calls = new AtomicInteger();
        StubResolver() { super((ChannelBindingService) null); }
        @Override public DefaultPrincipalPolicy policyFor(Ids.TenantId t, SenderId s) {
            calls.incrementAndGet();
            return next;
        }
        StubResolver thenReturn(DefaultPrincipalPolicy p) { this.next = p; return this; }
    }

    private final StubResolver resolver = new StubResolver();
    private final PhoneToPrincipalResolverRegistry registry =
            new PhoneToPrincipalResolverRegistry(List.of(), Duration.ofMinutes(5));
    private final PrincipalPolicyMaterializer materializer =
            new PrincipalPolicyMaterializer(registry);
    private final WhatsAppPhoneAuthenticator auth =
            new WhatsAppPhoneAuthenticator(resolver, materializer);

    @Test
    void mints_principal_when_channel_headers_present() throws AuthenticationException {
        AuthContext ctx = new AuthContext(
                new Ids.TenantId("acme"),
                Map.of(
                        WhatsAppPhoneAuthenticator.HEADER_END_USER, "+15551234567",
                        WhatsAppPhoneAuthenticator.HEADER_SENDER_ID, "snd_acme_main",
                        WhatsAppPhoneAuthenticator.HEADER_LOCALE, "es-DO"
                ),
                Optional.empty());
        Optional<Principal> result = auth.tryAuthenticate(ctx);
        assertThat(result).isPresent();
        Principal p = result.get();
        assertThat(p.subject()).isEqualTo("whatsapp:+15551234567");
        assertThat(p.tenant().value()).isEqualTo("acme");
        assertThat(p.scopes()).containsExactlyInAnyOrder("channel:whatsapp", "channel:read");
        assertThat(p.claims()).containsEntry("phone", "+15551234567");
        assertThat(p.claims()).containsEntry("channel.sender", "snd_acme_main");
        assertThat(p.claims()).containsEntry("channel.kind", "whatsapp");
        assertThat(p.claims()).containsEntry("locale", "es-DO");
    }

    @Test
    void empty_when_headers_absent() throws AuthenticationException {
        AuthContext ctx = new AuthContext(
                new Ids.TenantId("acme"),
                Map.of("Authorization", "Bearer something"),
                Optional.empty());
        assertThat(auth.tryAuthenticate(ctx)).isEmpty();
        assertThat(resolver.calls.get()).isZero();
    }

    @Test
    void throws_when_phone_malformed() {
        AuthContext ctx = new AuthContext(
                new Ids.TenantId("acme"),
                Map.of(
                        WhatsAppPhoneAuthenticator.HEADER_END_USER, "not-a-phone",
                        WhatsAppPhoneAuthenticator.HEADER_SENDER_ID, "snd_acme_main"
                ),
                Optional.empty());
        assertThatThrownBy(() -> auth.tryAuthenticate(ctx))
                .isInstanceOf(AuthenticationException.class)
                .hasMessageContaining("malformed end-user phone");
        assertThat(resolver.calls.get()).isZero();
    }

    @Test
    void header_lookup_is_case_insensitive() throws AuthenticationException {
        AuthContext ctx = new AuthContext(
                new Ids.TenantId("acme"),
                Map.of(
                        "X-CHANNEL-END-USER", "+15551234567",
                        "X-Channel-Sender-Id", "snd_x"
                ),
                Optional.empty());
        Optional<Principal> result = auth.tryAuthenticate(ctx);
        assertThat(result).isPresent();
        assertThat(result.get().subject()).isEqualTo("whatsapp:+15551234567");
    }

    @Test
    void phone_as_principal_policy_merges_extra_scopes_and_claims() throws AuthenticationException {
        resolver.thenReturn(new DefaultPrincipalPolicy.PhoneAsPrincipal(
                java.util.Set.of("order:read", "shipment:read"),
                java.util.Map.of("role", "guest")));
        AuthContext ctx = new AuthContext(
                new Ids.TenantId("acme"),
                Map.of(
                        WhatsAppPhoneAuthenticator.HEADER_END_USER, "+15551234567",
                        WhatsAppPhoneAuthenticator.HEADER_SENDER_ID, "snd_x"
                ),
                Optional.empty());
        Principal p = auth.tryAuthenticate(ctx).orElseThrow();
        assertThat(p.scopes()).containsExactlyInAnyOrder(
                "channel:whatsapp", "channel:read", "order:read", "shipment:read");
        assertThat(p.claims()).containsEntry("role", "guest");
        // channel-reserved claims still stamped
        assertThat(p.claims()).containsEntry("phone", "+15551234567");
        assertThat(p.claims()).containsEntry("channel.sender", "snd_x");
    }

    @Test
    void lookup_principal_with_no_registered_resolver_throws_illegal_state() {
        resolver.thenReturn(new DefaultPrincipalPolicy.LookupPrincipal("my-resolver", true));
        AuthContext ctx = new AuthContext(
                new Ids.TenantId("acme"),
                Map.of(
                        WhatsAppPhoneAuthenticator.HEADER_END_USER, "+15551234567",
                        WhatsAppPhoneAuthenticator.HEADER_SENDER_ID, "snd_x"
                ),
                Optional.empty());
        // No PhoneToPrincipalResolver beans registered → IllegalStateException
        // surfaces as an operator-misconfig alarm, not a tenant-fault denial.
        assertThatThrownBy(() -> auth.tryAuthenticate(ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("my-resolver");
    }
}
