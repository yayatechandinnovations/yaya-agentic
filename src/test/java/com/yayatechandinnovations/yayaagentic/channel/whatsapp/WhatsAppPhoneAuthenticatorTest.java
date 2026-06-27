package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.auth.AuthenticationException;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WhatsAppPhoneAuthenticatorTest {

    private final WhatsAppPhoneAuthenticator auth = new WhatsAppPhoneAuthenticator();

    @Test
    void mints_baseline_principal_when_channel_headers_present() throws AuthenticationException {
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
    void twilio_contact_sid_header_is_stamped_as_claim() throws AuthenticationException {
        AuthContext ctx = new AuthContext(
                new Ids.TenantId("acme"),
                Map.of(
                        WhatsAppPhoneAuthenticator.HEADER_END_USER, "+15551234567",
                        WhatsAppPhoneAuthenticator.HEADER_SENDER_ID, "snd_x",
                        WhatsAppPhoneAuthenticator.HEADER_TWILIO_FROM, "CT_abc123"
                ),
                Optional.empty());
        Principal p = auth.tryAuthenticate(ctx).orElseThrow();
        assertThat(p.claims()).containsEntry("twilioContactSid", "CT_abc123");
    }
}
