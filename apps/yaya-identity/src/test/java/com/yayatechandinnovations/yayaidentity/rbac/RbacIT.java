package com.yayatechandinnovations.yayaidentity.rbac;

import com.yayatechandinnovations.yayaidentity.groups.Group;
import com.yayatechandinnovations.yayaidentity.groups.GroupService;
import com.yayatechandinnovations.yayaidentity.organizations.Organization;
import com.yayatechandinnovations.yayaidentity.organizations.OrganizationService;
import com.yayatechandinnovations.yayaidentity.platform.Ids.PermissionKey;
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
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * I2 RBAC (US-30..US-36, FR-3.x). The headline: a role scoped to store A grants
 * its permissions on store A and nothing on store B; a group assignment does the
 * same for every member; the token carries the scoped {@code roles} claim.
 */
@SpringBootTest
@Testcontainers
class RbacIT {

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

    @Autowired PermissionCatalogService catalog;
    @Autowired RoleService roles;
    @Autowired RoleAssignmentService assignments;
    @Autowired OrganizationService orgs;
    @Autowired UserService users;
    @Autowired GroupService groups;

    private static Set<String> keys(Set<PermissionKey> perms) {
        return perms.stream().map(PermissionKey::value).collect(Collectors.toSet());
    }

    @Test
    void systemRolesAreBootstrapped() {
        assertThat(roles.systemRole("owner")).isPresent();
        assertThat(roles.systemRole("admin")).isPresent();
        assertThat(roles.systemRole("member")).isPresent();
    }

    @Test
    void permissionsMustBeDeclaredBeforeUseInARole() {
        Organization org = orgs.create("Catalog Org");
        // A key no other test declares, so this is order-independent under the shared DB.
        assertThatThrownBy(() -> roles.createRole(org.id(), "bad", List.of("billing.invoice:void")))
                .isInstanceOf(IllegalArgumentException.class);   // not declared yet
    }

    @Test
    void scopedRoleGrantsOnOneResourceOnly() {
        catalog.declare("sales", List.of("sales.order:refund", "sales.order:read"));
        Organization org = orgs.create("Scoped Org");
        Role manager = roles.createRole(org.id(), "store-manager",
                List.of("sales.order:refund", "sales.order:read"));
        User u = users.createPerson("mgr@acme.test", null, "Mgr");
        orgs.addMember(org.id(), u.id());

        assignments.assign(org.id(), AssignmentSubject.user(u.id()), manager.id(),
                new Scope.Resource("store", "st_A"));

        // Full permissions on store A …
        assertThat(keys(assignments.effectivePermissions(u.id(), org.id(),
                new Scope.Resource("store", "st_A"))))
                .containsExactlyInAnyOrder("sales.order:refund", "sales.order:read");
        // … nothing on store B …
        assertThat(assignments.effectivePermissions(u.id(), org.id(),
                new Scope.Resource("store", "st_B"))).isEmpty();
        // … and a resource grant does not confer org-wide rights.
        assertThat(assignments.effectivePermissions(u.id(), org.id(),
                new Scope.Org(org.id()))).isEmpty();
    }

    @Test
    void orgScopedRoleCoversEveryResource() {
        catalog.declare("sales", List.of("sales.order:read"));
        Organization org = orgs.create("Org Wide");
        Role viewer = roles.createRole(org.id(), "org-viewer", List.of("sales.order:read"));
        User u = users.createPerson("viewer@acme.test", null, "Viewer");
        orgs.addMember(org.id(), u.id());

        assignments.assign(org.id(), AssignmentSubject.user(u.id()), viewer.id(),
                new Scope.Org(org.id()));

        assertThat(keys(assignments.effectivePermissions(u.id(), org.id(),
                new Scope.Resource("store", "any")))).contains("sales.order:read");
    }

    @Test
    void groupAssignmentAppliesToEveryMember() {
        catalog.declare("sales", List.of("sales.delivery:collect-cod"));
        Organization org = orgs.create("Fleet Org");
        Role courier = roles.createRole(org.id(), "courier", List.of("sales.delivery:collect-cod"));
        User u = users.createPerson("courier@acme.test", null, "Courier");
        orgs.addMember(org.id(), u.id());
        Group fleet = groups.create(org.id(), "downtown-fleet");
        groups.addMember(fleet.id(), u.id());

        assignments.assign(org.id(), AssignmentSubject.group(fleet.id()), courier.id(),
                new Scope.Resource("store", "st_C"));

        assertThat(keys(assignments.effectivePermissions(u.id(), org.id(),
                new Scope.Resource("store", "st_C")))).contains("sales.delivery:collect-cod");
        assertThat(assignments.effectivePermissions(u.id(), org.id(),
                new Scope.Resource("store", "st_D"))).isEmpty();
    }

    @Test
    void tokenRolesCarryScope() {
        catalog.declare("sales", List.of("sales.order:read"));
        Organization org = orgs.create("Token Roles Org");
        Role manager = roles.createRole(org.id(), "store-manager", List.of("sales.order:read"));
        User u = users.createPerson("claims@acme.test", null, "Claims");
        orgs.addMember(org.id(), u.id());
        assignments.assign(org.id(), AssignmentSubject.user(u.id()), manager.id(),
                new Scope.Resource("store", "st_A"));

        List<Map<String, String>> tokenRoles = assignments.tokenRoles(u.id(), org.id());
        assertThat(tokenRoles).containsExactly(Map.of("role", "store-manager", "scope", "store:st_A"));
    }
}
