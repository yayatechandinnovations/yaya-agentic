package com.yayatechandinnovations.yayaagentic.channel.sms;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.auth.AuthenticationException;
import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmsPhoneAuthenticatorTest {

    private final SmsPhoneAuthenticator auth = new SmsPhoneAuthenticator();

    private AuthContext ctx(Map<String, String> headers) {
        return new AuthContext(new TenantId("t_acme"), headers, Optional.empty());
    }

    @Test
    void mints_sms_principal_from_reserved_headers() throws Exception {
        Optional<Principal> p = auth.tryAuthenticate(ctx(Map.of(
                SmsPhoneAuthenticator.HEADER_END_USER, "+18095551234",
                SmsPhoneAuthenticator.HEADER_SENDER_ID, "sms_1",
                SmsPhoneAuthenticator.HEADER_LOCALE, "es-DO")));

        assertThat(p).isPresent();
        assertThat(p.get().subject()).isEqualTo("sms:+18095551234");
        assertThat(p.get().scopes()).contains("channel:sms", "channel:read");
        assertThat(p.get().claims())
                .containsEntry("phone", "+18095551234")
                .containsEntry("channel.kind", "sms")
                .containsEntry("locale", "es-DO");
    }

    @Test
    void abstains_when_sms_headers_absent() throws Exception {
        // WhatsApp's headers must NOT trigger the SMS authenticator
        assertThat(auth.tryAuthenticate(ctx(Map.of(
                "x-channel-end-user", "+18095551234",
                "x-channel-sender-id", "wa_1")))).isEmpty();
    }

    @Test
    void rejects_malformed_phone() {
        assertThatThrownBy(() -> auth.tryAuthenticate(ctx(Map.of(
                SmsPhoneAuthenticator.HEADER_END_USER, "18095551234",   // no +
                SmsPhoneAuthenticator.HEADER_SENDER_ID, "sms_1"))))
                .isInstanceOf(AuthenticationException.class);
    }
}
