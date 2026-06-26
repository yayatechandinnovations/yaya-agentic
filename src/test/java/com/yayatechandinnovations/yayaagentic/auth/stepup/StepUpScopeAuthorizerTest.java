package com.yayatechandinnovations.yayaagentic.auth.stepup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.auth.AuthzContext;
import com.yayatechandinnovations.yayaagentic.auth.AuthzDecision;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.persistence.ChannelSessionElevationEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChannelSessionElevationRepository;
import com.yayatechandinnovations.yayaagentic.persistence.StepUpAuditRepository;
import com.yayatechandinnovations.yayaagentic.persistence.StepUpPendingRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StepUpScopeAuthorizerTest {

    private final AuthzContext ctx = new AuthzContext(
            new Ids.SessionId("sess-1"), null, "trace",
            Map.of("toolId", "test"));

    private static StepUpAuthorizerProperties.Rule rule(String tenant,
                                                        Set<String> guardedScopes,
                                                        String audience,
                                                        String method) {
        var r = new StepUpAuthorizerProperties.Rule();
        r.setTenant(tenant);
        r.setGuardedScopes(guardedScopes);
        r.setAudience(audience);
        r.setMethod(method);
        r.setElevationTtl(Duration.ofMinutes(30));
        r.setUserSafeReason("Please verify it's you.");
        return r;
    }

    private static StepUpAuthorizerProperties propsWith(List<StepUpAuthorizerProperties.Rule> rs) {
        var p = new StepUpAuthorizerProperties();
        p.setRules(rs);
        return p;
    }

    private static Principal principal(String tenant, Set<String> scopes) {
        return new Principal("u_42", new Ids.TenantId(tenant),
                scopes, Map.of(), Instant.now());
    }

    /** Wraps the (props, coordinator) constructor with a no-elevation default. */
    private StepUpScopeAuthorizer authWith(List<StepUpAuthorizerProperties.Rule> rs) {
        return new StepUpScopeAuthorizer(propsWith(rs),
                stubCoordinator(Optional.empty()));
    }

    /** Build a coordinator whose {@code activeElevation} returns the given
     *  row regardless of session id. */
    private static StepUpCoordinator stubCoordinator(
            Optional<ChannelSessionElevationEntity> elevation) {
        var elevationsRepo = mock(ChannelSessionElevationRepository.class);
        when(elevationsRepo.findBySessionId(anyString())).thenReturn(elevation);
        return new StepUpCoordinator(
                mock(StepUpPendingRepository.class),
                mock(StepUpAuditRepository.class),
                elevationsRepo,
                new ObjectMapper(),
                List.of());
    }

    @Test
    void allows_when_no_rule_matches() {
        var auth = authWith(List.of(
                rule("acme", Set.of("tool:transfer_funds"),
                        "tool:transfer_funds", "OtpEmail")));
        AuthzDecision d = auth.authorize(
                principal("acme", Set.of("tool:read_orders")),
                new PermissionRequirement(Set.of("tool:read_orders"), List.of(), null),
                Map.of(), ctx);
        assertThat(d).isInstanceOf(AuthzDecision.Allow.class);
    }

    @Test
    void allows_when_rule_matches_and_principal_already_elevated() {
        var auth = authWith(List.of(
                rule("acme", Set.of("tool:transfer_funds"),
                        "tool:transfer_funds", "OtpEmail")));
        AuthzDecision d = auth.authorize(
                principal("acme", Set.of("tool:transfer_funds",
                        "stepup:tool:transfer_funds")),
                new PermissionRequirement(
                        Set.of("tool:transfer_funds"), List.of(), null),
                Map.of(), ctx);
        assertThat(d).isInstanceOf(AuthzDecision.Allow.class);
    }

    @Test
    void emits_deny_offer_step_up_when_rule_matches_and_no_elevation() {
        var auth = authWith(List.of(
                rule("acme", Set.of("tool:transfer_funds"),
                        "tool:transfer_funds", "OtpEmail")));
        AuthzDecision d = auth.authorize(
                principal("acme", Set.of("tool:transfer_funds")),
                new PermissionRequirement(
                        Set.of("tool:transfer_funds"), List.of(), null),
                Map.of(), ctx);
        assertThat(d).isInstanceOf(AuthzDecision.DenyOfferStepUp.class);
        AuthzDecision.DenyOfferStepUp ds = (AuthzDecision.DenyOfferStepUp) d;
        assertThat(ds.hint().audience()).isEqualTo("tool:transfer_funds");
        assertThat(ds.hint().method()).isInstanceOf(StepUpMethod.OtpEmail.class);
        assertThat(ds.userSafeReason()).contains("verify");
        assertThat(ds.auditReason()).contains("u_42")
                .contains("stepup:tool:transfer_funds");
    }

    @Test
    void rules_are_tenant_isolated() {
        var auth = authWith(List.of(
                rule("acme", Set.of("tool:transfer_funds"),
                        "tool:transfer_funds", "OtpEmail")));
        AuthzDecision d = auth.authorize(
                principal("beta", Set.of("tool:transfer_funds")),
                new PermissionRequirement(
                        Set.of("tool:transfer_funds"), List.of(), null),
                Map.of(), ctx);
        assertThat(d).isInstanceOf(AuthzDecision.Allow.class);
    }

    @Test
    void rule_matches_on_any_overlap_with_required_scopes() {
        var auth = authWith(List.of(
                rule("acme", Set.of("tool:transfer_funds", "tool:wire_money"),
                        "money_movement", "OtpEmail")));
        AuthzDecision d = auth.authorize(
                principal("acme", Set.of("tool:wire_money")),
                new PermissionRequirement(
                        Set.of("tool:wire_money"), List.of(), null),
                Map.of(), ctx);
        assertThat(d).isInstanceOf(AuthzDecision.DenyOfferStepUp.class);
        var ds = (AuthzDecision.DenyOfferStepUp) d;
        assertThat(ds.hint().audience()).isEqualTo("money_movement");
    }

    @Test
    void allows_when_requirement_has_no_required_scopes() {
        var auth = authWith(List.of(
                rule("acme", Set.of("tool:transfer_funds"),
                        "tool:transfer_funds", "OtpEmail")));
        AuthzDecision d = auth.authorize(
                principal("acme", Set.of()),
                new PermissionRequirement(Set.of(), List.of(), null),
                Map.of(), ctx);
        assertThat(d).isInstanceOf(AuthzDecision.Allow.class);
    }

    @Test
    void rule_carries_configured_elevation_ttl_into_hint() {
        var r = rule("acme", Set.of("tool:x"), "tool:x", "OtpEmail");
        r.setElevationTtl(Duration.ofMinutes(5));
        var auth = authWith(List.of(r));
        var d = (AuthzDecision.DenyOfferStepUp) auth.authorize(
                principal("acme", Set.of()),
                new PermissionRequirement(Set.of("tool:x"), List.of(), null),
                Map.of(), ctx);
        assertThat(d.hint().elevationTtl()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void unknown_method_name_in_rule_throws_at_match_time() {
        var r = rule("acme", Set.of("tool:x"), "tool:x", "BogusMethod");
        var auth = authWith(List.of(r));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> auth.authorize(
                principal("acme", Set.of()),
                new PermissionRequirement(Set.of("tool:x"), List.of(), null),
                Map.of(), ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BogusMethod");
    }

    @Test
    void custom_method_format_resolves_to_custom_provider_id() {
        var r = rule("acme", Set.of("tool:x"), "tool:x", "Custom:my-provider");
        var auth = authWith(List.of(r));
        var d = (AuthzDecision.DenyOfferStepUp) auth.authorize(
                principal("acme", Set.of()),
                new PermissionRequirement(Set.of("tool:x"), List.of(), null),
                Map.of(), ctx);
        assertThat(d.hint().method()).isInstanceOf(StepUpMethod.Custom.class);
        assertThat(((StepUpMethod.Custom) d.hint().method()).providerId())
                .isEqualTo("my-provider");
    }

    @Test
    void allows_when_active_elevation_carries_the_required_stepup_scope() {
        // The session's principal doesn't carry stepup:tool:transfer_funds in
        // its cached scopes, but the channel_session_elevation row does. The
        // authorizer must consult the elevation row before denying.
        var elevation = new ChannelSessionElevationEntity(
                "sess-1", "acme", "u_42",
                new String[] {"stepup:tool:transfer_funds"},
                "{}", "tool:transfer_funds", "otp_email",
                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30),
                null);
        var auth = new StepUpScopeAuthorizer(
                propsWith(List.of(rule("acme", Set.of("tool:transfer_funds"),
                        "tool:transfer_funds", "OtpEmail"))),
                stubCoordinator(Optional.of(elevation)));
        AuthzDecision d = auth.authorize(
                principal("acme", Set.of("tool:transfer_funds")),
                new PermissionRequirement(
                        Set.of("tool:transfer_funds"), List.of(), null),
                Map.of(), ctx);
        assertThat(d).isInstanceOf(AuthzDecision.Allow.class);
    }
}
