package com.yayatechandinnovations.yayaagentic.channel.whatsapp.auth;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrincipalPolicyMaterializerTest {

    private final PrincipalPolicyMaterializer materializer = new PrincipalPolicyMaterializer();

    private static AuthContext ctxWith(Map<String, String> headers) {
        return new AuthContext(new Ids.TenantId("acme"), headers, Optional.empty());
    }

    @Test
    void anonymous_produces_minimal_scopes_and_reserved_claims_only() {
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
    void anonymous_drops_locale_when_header_absent() {
        Principal p = materializer.materialize(
                new DefaultPrincipalPolicy.Anonymous(),
                "+15551234567",
                "snd_x",
                ctxWith(Map.of()));
        assertThat(p.claims()).doesNotContainKey("locale");
        assertThat(p.claims()).doesNotContainKey("twilioContactSid");
    }

    @Test
    void phone_as_principal_merges_extra_scopes_above_baseline() {
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
    void phone_as_principal_extra_scopes_de_duplicate_against_baseline() {
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
    void lookup_principal_throws_until_W_A2() {
        DefaultPrincipalPolicy l = new DefaultPrincipalPolicy.LookupPrincipal("acme-resolver", false);
        assertThatThrownBy(() -> materializer.materialize(
                l, "+15551234567", "snd_x", ctxWith(Map.of())))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("W-A2")
                .hasMessageContaining("acme-resolver");
    }
}
