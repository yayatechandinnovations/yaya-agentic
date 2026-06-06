package com.yayatechandinnovations.yayaagentic.auth;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.persistence.AuditAuthzRepository;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the {@link Authorizer} chain end-to-end (real Postgres) and
 * asserts every decision lands in {@code audit_authz} with distinct
 * user-safe and audit reasons. Design §5.5.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AuthorizerChainTest {

    @Autowired Authorizer chain;
    @Autowired AuditAuthzRepository audit;

    @Test
    void denies_when_required_scope_missing_and_audits_it() {
        long before = audit.count();
        Principal p = new Principal("user-42", new Ids.TenantId("default"),
                Set.of("user.read"), Map.of(), Instant.now());
        PermissionRequirement req = new PermissionRequirement(
                Set.of("user.write"), List.of(), null);
        AuthzContext ctx = new AuthzContext(null, null, "trace-1",
                Map.of("toolId", "test-tool"));

        AuthzDecision decision = chain.authorize(p, req, Map.of("k", "v"), ctx);

        assertThat(decision).isInstanceOf(AuthzDecision.Deny.class);
        AuthzDecision.Deny deny = (AuthzDecision.Deny) decision;
        assertThat(deny.userSafeReason()).doesNotContain("user.write");
        assertThat(deny.auditReason()).contains("user.write");
        assertThat(audit.count()).isGreaterThan(before);
    }

    @Test
    void denies_when_ownership_mismatched_and_audits_it() {
        long before = audit.count();
        Principal p = new Principal("user-42", new Ids.TenantId("default"),
                Set.of(), Map.of("accounts", List.of("1234")), Instant.now());
        PermissionRequirement req = new PermissionRequirement(
                Set.of(), List.of(),
                new PermissionRequirement.ResourceOwnership("account_id", "accounts"));
        AuthzContext ctx = new AuthzContext(null, null, "trace-2", Map.of());

        AuthzDecision decision = chain.authorize(p, req, Map.of("account_id", "998877"), ctx);

        assertThat(decision).isInstanceOf(AuthzDecision.Deny.class);
        AuthzDecision.Deny deny = (AuthzDecision.Deny) decision;
        assertThat(deny.userSafeReason()).contains("isn't linked to your profile");
        assertThat(deny.auditReason()).contains("998877");
        assertThat(audit.count()).isGreaterThan(before);
    }

    @Test
    void allows_when_ownership_matches() {
        long before = audit.count();
        Principal p = new Principal("user-42", new Ids.TenantId("default"),
                Set.of(), Map.of("accounts", List.of("1234")), Instant.now());
        PermissionRequirement req = new PermissionRequirement(
                Set.of(), List.of(),
                new PermissionRequirement.ResourceOwnership("account_id", "accounts"));
        AuthzContext ctx = new AuthzContext(null, null, "trace-3", Map.of());

        AuthzDecision decision = chain.authorize(p, req, Map.of("account_id", "1234"), ctx);

        assertThat(decision).isInstanceOf(AuthzDecision.Allow.class);
        assertThat(audit.count()).isGreaterThan(before);
    }
}
