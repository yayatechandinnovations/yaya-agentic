package com.yayatechandinnovations.yayaagentic.channel.whatsapp.auth;

import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class StaticPhoneToPrincipalResolverTest {

    private static final SenderId SENDER = new SenderId("snd_main");
    private static final ResolutionContext CTX =
            new ResolutionContext(Instant.now(), Optional.empty(), Optional.empty(), Map.of());

    @Test
    void uses_default_id_when_unset() {
        var r = new StaticPhoneToPrincipalResolver();
        assertThat(r.id()).isEqualTo("static-phone-lookup");
        assertThat(r.description()).isNotBlank();
    }

    @Test
    void respects_operator_supplied_id_and_description() {
        var r = new StaticPhoneToPrincipalResolver();
        r.setId("retail-customer-lookup");
        r.setDescription("Maps phones to retail customer IDs");
        assertThat(r.id()).isEqualTo("retail-customer-lookup");
        assertThat(r.description()).isEqualTo("Maps phones to retail customer IDs");
    }

    @Test
    void returns_empty_for_unknown_phone() {
        var r = new StaticPhoneToPrincipalResolver();
        r.setMappings(List.of());
        Optional<Principal> result = r.resolve(
                new Ids.TenantId("acme"), SENDER, "+15551234567", CTX);
        assertThat(result).isEmpty();
    }

    @Test
    void resolves_phone_to_principal_with_configured_scopes_and_claims() {
        var r = new StaticPhoneToPrincipalResolver();
        r.setMappings(List.of(new StaticPhoneToPrincipalResolver.Mapping(
                "acme",
                "+15551234567",
                "u_42",
                List.of("order:read", "shipment:read"),
                Map.of("tier", "gold"))));

        Optional<Principal> result = r.resolve(
                new Ids.TenantId("acme"), SENDER, "+15551234567", CTX);
        assertThat(result).isPresent();
        Principal p = result.get();
        assertThat(p.subject()).isEqualTo("u_42");
        assertThat(p.scopes()).containsExactlyInAnyOrder("order:read", "shipment:read");
        assertThat(p.claims()).containsEntry("tier", "gold");
    }

    @Test
    void mappings_are_tenant_scoped() {
        var r = new StaticPhoneToPrincipalResolver();
        r.setMappings(List.of(new StaticPhoneToPrincipalResolver.Mapping(
                "acme", "+15551234567", "u_acme_42", List.of(), Map.of())));

        // Same phone, different tenant — must NOT cross over
        Optional<Principal> beta = r.resolve(
                new Ids.TenantId("beta"), SENDER, "+15551234567", CTX);
        assertThat(beta).isEmpty();
    }

    @Test
    void handles_null_scopes_and_claims_in_mapping_gracefully() {
        var r = new StaticPhoneToPrincipalResolver();
        r.setMappings(List.of(new StaticPhoneToPrincipalResolver.Mapping(
                "acme", "+15551234567", "u_42", null, null)));

        Optional<Principal> result = r.resolve(
                new Ids.TenantId("acme"), SENDER, "+15551234567", CTX);
        assertThat(result).isPresent();
        assertThat(result.get().scopes()).isEmpty();
        assertThat(result.get().claims()).isEmpty();
    }
}
