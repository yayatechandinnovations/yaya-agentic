package com.yayatechandinnovations.yayaidentity.policies;

import com.yayatechandinnovations.yayaidentity.organizations.Organization;
import com.yayatechandinnovations.yayaidentity.organizations.OrganizationService;
import com.yayatechandinnovations.yayaidentity.platform.Ids.OrgId;
import com.yayatechandinnovations.yayaidentity.platform.audit.AuditLogRepository;
import com.yayatechandinnovations.yayaidentity.roles.AssignmentSubject;
import com.yayatechandinnovations.yayaidentity.roles.PermissionCatalogService;
import com.yayatechandinnovations.yayaidentity.roles.Role;
import com.yayatechandinnovations.yayaidentity.roles.RoleAssignmentService;
import com.yayatechandinnovations.yayaidentity.roles.RoleService;
import com.yayatechandinnovations.yayaidentity.roles.Scope;
import com.yayatechandinnovations.yayaidentity.users.User;
import com.yayatechandinnovations.yayaidentity.users.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * I3 — the PDP (US-40..US-48, FR-4.x). Permit via RBAC, DENY policy overriding a
 * permit with two distinct reasons, default-deny, an ownership PERMIT policy that
 * grants what RBAC didn't, batch, and simulate-as-dry-run (no audit).
 */
@SpringBootTest
@Testcontainers
class PdpIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("yaya_identity");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("yaya.identity.secrets.encryption-key",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired PolicyDecisionPoint pdp;
    @Autowired PolicyService policyService;
    @Autowired PermissionCatalogService catalog;
    @Autowired RoleService roles;
    @Autowired RoleAssignmentService assignments;
    @Autowired OrganizationService orgs;
    @Autowired UserService users;
    @Autowired AuditLogRepository auditLog;

    /** A member with a role granting {@code permission} on {@code store}. Returns (org, userId). */
    private Manager manager(String orgName, String email, String permission, String store) {
        catalog.declare("sales", List.of(permission));
        Organization org = orgs.create(orgName);
        Role role = roles.createRole(org.id(), "role-" + email, List.of(permission));
        User u = users.createPerson(email, null, "U");
        orgs.addMember(org.id(), u.id());
        assignments.assign(org.id(), AssignmentSubject.user(u.id()), role.id(),
                new Scope.Resource("store", store));
        return new Manager(org.id(), u.id().value());
    }

    private record Manager(OrgId org, String userId) {}

    @Test
    void rbacPermitWithNoPolicies() {
        Manager m = manager("PDP Permit", "permit@acme.test", "sales.order:read", "st_A");
        Decision d = pdp.decide(new DecisionRequest(m.org().value(), m.userId(), "sales.order:read",
                Map.of("type", "store", "id", "st_A")));
        assertThat(d.isPermit()).isTrue();
    }

    @Test
    void denyPolicyOverridesRbacPermitWithTwoReasons() {
        Manager m = manager("PDP Refund", "refund@acme.test", "sales.order:refund", "st_A");
        policyService.create(m.org(), "refund-cap", Policy.Effect.DENY,
                "{\"actions\":[\"sales.order:refund\"]}",
                "{\">\":[{\"var\":\"resource.amount\"},{\"var\":\"subject.refundLimit\"}]}",
                100,
                "You can't refund orders over your limit.",
                "policy refund-cap: amount exceeds role refund limit");

        Decision over = pdp.decide(new DecisionRequest(m.org().value(), m.userId(), "sales.order:refund",
                Map.of("type", "store", "id", "st_A", "amount", 750),
                Map.of(), Map.of("refundLimit", 500)));
        assertThat(over).isInstanceOf(Decision.Deny.class);
        Decision.Deny deny = (Decision.Deny) over;
        assertThat(deny.userSafeReason()).isNotEqualTo(deny.auditReason());   // #3

        Decision under = pdp.decide(new DecisionRequest(m.org().value(), m.userId(), "sales.order:refund",
                Map.of("type", "store", "id", "st_A", "amount", 300),
                Map.of(), Map.of("refundLimit", 500)));
        assertThat(under.isPermit()).isTrue();
    }

    @Test
    void defaultDeniesUngrantedAction() {
        Manager m = manager("PDP Default", "default@acme.test", "sales.order:read", "st_A");
        Decision d = pdp.decide(new DecisionRequest(m.org().value(), m.userId(), "sales.order:delete",
                Map.of("type", "store", "id", "st_A")));
        assertThat(d).isInstanceOf(Decision.Deny.class);
        assertThat(((Decision.Deny) d).userSafeReason()).doesNotContain("default-deny");   // generic to the user
    }

    @Test
    void permitPolicyGrantsOwnershipWhatRbacDidNot() {
        catalog.declare("sales", List.of("sales.delivery:collect-cod"));
        Organization org = orgs.create("PDP Ownership");
        User courier = users.createPerson("owner-courier@acme.test", null, "Courier");
        orgs.addMember(org.id(), courier.id());   // member, but no RBAC permission for collect-cod
        policyService.create(org.id(), "cod-ownership", Policy.Effect.PERMIT,
                "{\"actions\":[\"sales.delivery:collect-cod\"]}",
                "{\"==\":[{\"var\":\"resource.assignedAgent\"},{\"var\":\"subject.id\"}]}",
                50, null, null);

        Decision mine = pdp.decide(new DecisionRequest(org.id().value(), courier.id().value(),
                "sales.delivery:collect-cod",
                Map.of("type", "store", "id", "st_A", "assignedAgent", courier.id().value())));
        assertThat(mine.isPermit()).isTrue();

        Decision notMine = pdp.decide(new DecisionRequest(org.id().value(), courier.id().value(),
                "sales.delivery:collect-cod",
                Map.of("type", "store", "id", "st_A", "assignedAgent", "someone-else")));
        assertThat(notMine).isInstanceOf(Decision.Deny.class);
    }

    @Test
    void batchAnswersManyInOneCall() {
        Manager m = manager("PDP Batch", "batch@acme.test", "sales.order:read", "st_A");
        List<Decision> out = pdp.decideBatch(List.of(
                new DecisionRequest(m.org().value(), m.userId(), "sales.order:read",
                        Map.of("type", "store", "id", "st_A")),
                new DecisionRequest(m.org().value(), m.userId(), "sales.order:read",
                        Map.of("type", "store", "id", "st_B"))));
        assertThat(out.get(0).isPermit()).isTrue();     // granted on A
        assertThat(out.get(1)).isInstanceOf(Decision.Deny.class);   // not on B
    }

    @Test
    void simulateDoesNotAuditButDecideDoes() {
        Manager m = manager("PDP Simulate", "sim@acme.test", "sales.order:read", "st_A");
        DecisionRequest req = new DecisionRequest(m.org().value(), m.userId(), "sales.order:read",
                Map.of("type", "store", "id", "st_A"));

        long before = auditLog.count();
        pdp.simulate(req);
        assertThat(auditLog.count()).isEqualTo(before);     // dry-run leaves no trace

        pdp.decide(req);
        assertThat(auditLog.count()).isEqualTo(before + 1); // a real decision is audited
    }

    @Test
    void denyMustCarryTwoDistinctReasons() {
        assertThatThrownBy(() -> new Decision.Deny("same", "same"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
