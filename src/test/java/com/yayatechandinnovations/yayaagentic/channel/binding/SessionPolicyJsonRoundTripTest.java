package com.yayatechandinnovations.yayaagentic.channel.binding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.auth.DefaultPrincipalPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Defends the Jackson polymorphism wiring on {@link DefaultPrincipalPolicy} as
 * persisted inside {@code channel_bindings.session_policy_json}. A regression
 * here would silently downgrade every binding to Anonymous on read.
 *
 * <p>JSR-310 is registered explicitly because the production {@code ObjectMapper}
 * is Spring-auto-configured with the module; an isolated unit test must mirror
 * that or every Duration field rejects deserialization.
 */
class SessionPolicyJsonRoundTripTest {

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void defaults_round_trip_lossless() throws Exception {
        SessionPolicy original = SessionPolicy.defaults();
        String encoded = json.writeValueAsString(original);
        assertThat(encoded).contains("\"kind\":\"Anonymous\"");
        SessionPolicy decoded = json.readValue(encoded, SessionPolicy.class);
        assertThat(decoded.defaultPrincipalPolicy())
                .isInstanceOf(DefaultPrincipalPolicy.Anonymous.class);
    }

    @Test
    void phone_as_principal_round_trips_scopes_and_claims() throws Exception {
        SessionPolicy original = new SessionPolicy(
                Duration.ofHours(12),
                Set.of("#new"),
                Set.of("bye"),
                false, null, true,
                new DefaultPrincipalPolicy.PhoneAsPrincipal(
                        Set.of("order:read", "shipment:read"),
                        Map.of("role", "guest")));
        String encoded = json.writeValueAsString(original);
        assertThat(encoded).contains("\"kind\":\"PhoneAsPrincipal\"");
        SessionPolicy decoded = json.readValue(encoded, SessionPolicy.class);
        var policy = (DefaultPrincipalPolicy.PhoneAsPrincipal) decoded.defaultPrincipalPolicy();
        assertThat(policy.additionalScopes()).containsExactlyInAnyOrder(
                "order:read", "shipment:read");
        assertThat(policy.additionalClaims()).containsEntry("role", "guest");
    }

    @Test
    void lookup_principal_round_trips_resolver_id_and_deny_flag() throws Exception {
        SessionPolicy original = new SessionPolicy(
                Duration.ofHours(24),
                Set.of(), Set.of(),
                false, null, true,
                new DefaultPrincipalPolicy.LookupPrincipal("acme-resolver", true));
        String encoded = json.writeValueAsString(original);
        assertThat(encoded).contains("\"kind\":\"LookupPrincipal\"");
        SessionPolicy decoded = json.readValue(encoded, SessionPolicy.class);
        var policy = (DefaultPrincipalPolicy.LookupPrincipal) decoded.defaultPrincipalPolicy();
        assertThat(policy.resolverId()).isEqualTo("acme-resolver");
        assertThat(policy.denyOnUnknown()).isTrue();
    }

    @Test
    void legacy_payload_without_default_principal_policy_decodes_as_anonymous() throws Exception {
        // Simulates a row written before W-A1 — the JSON has no
        // defaultPrincipalPolicy field. Decode must succeed and yield Anonymous.
        String legacy = """
                {
                  "idleTimeout": "PT24H",
                  "newSessionKeywords": ["#new"],
                  "endSessionKeywords": ["bye"],
                  "greetOnReengagement": false,
                  "reengagementTemplate": null,
                  "exposeEndUserPhoneToProfile": true
                }
                """;
        SessionPolicy decoded = json.readValue(legacy, SessionPolicy.class);
        assertThat(decoded.defaultPrincipalPolicy())
                .isInstanceOf(DefaultPrincipalPolicy.Anonymous.class);
    }
}
