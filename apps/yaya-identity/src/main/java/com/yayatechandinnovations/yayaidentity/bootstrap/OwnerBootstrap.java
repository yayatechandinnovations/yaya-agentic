package com.yayatechandinnovations.yayaidentity.bootstrap;

import com.yayatechandinnovations.yayaidentity.credentials.CredentialService;
import com.yayatechandinnovations.yayaidentity.organizations.Organization;
import com.yayatechandinnovations.yayaidentity.organizations.OrganizationService;
import com.yayatechandinnovations.yayaidentity.platform.Ids.OrgId;
import com.yayatechandinnovations.yayaidentity.roles.AssignmentSubject;
import com.yayatechandinnovations.yayaidentity.roles.Role;
import com.yayatechandinnovations.yayaidentity.roles.RoleAssignmentService;
import com.yayatechandinnovations.yayaidentity.roles.RoleService;
import com.yayatechandinnovations.yayaidentity.roles.Scope;
import com.yayatechandinnovations.yayaidentity.users.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * First-access bootstrap. yaya-identity has no human users out of the box, so a
 * fresh install can't be logged into. When an owner email + password are
 * configured, this creates (idempotently, once) a root org and an ACTIVE user
 * with the system {@code owner} role scoped to that org — the "master user" an
 * operator uses to sign into the console the first time.
 *
 * <p>Runs after {@code RoleService} has bootstrapped the system roles. A
 * misconfiguration (e.g. too-short password) is logged, not fatal — the service
 * still boots and can issue machine tokens.
 */
@Component
@Order(100)
public class OwnerBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OwnerBootstrap.class);

    private final OrganizationService orgs;
    private final com.yayatechandinnovations.yayaidentity.users.UserService users;
    private final CredentialService credentials;
    private final RoleService roles;
    private final RoleAssignmentService assignments;
    private final String email;
    private final String password;
    private final String orgName;

    public OwnerBootstrap(OrganizationService orgs,
                          com.yayatechandinnovations.yayaidentity.users.UserService users,
                          CredentialService credentials, RoleService roles,
                          RoleAssignmentService assignments,
                          @Value("${yaya.identity.bootstrap.owner.email:}") String email,
                          @Value("${yaya.identity.bootstrap.owner.password:}") String password,
                          @Value("${yaya.identity.bootstrap.owner.org-name:Root Org}") String orgName) {
        this.orgs = orgs;
        this.users = users;
        this.credentials = credentials;
        this.roles = roles;
        this.assignments = assignments;
        this.email = email;
        this.password = password;
        this.orgName = orgName;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            return;   // no bootstrap owner configured — machine-only install
        }
        if (users.byEmail(email).isPresent()) {
            return;   // already bootstrapped
        }
        try {
            Organization org = orgs.create(orgName);
            User owner = users.createPerson(email, null, "Owner");
            credentials.setPassword(owner.id(), password);
            users.setStatus(owner.id(), User.Status.ACTIVE);
            orgs.addMember(org.id(), owner.id());
            Role ownerRole = roles.systemRole("owner")
                    .orElseThrow(() -> new IllegalStateException("system role 'owner' missing"));
            assignments.assign(org.id(), AssignmentSubject.user(owner.id()), ownerRole.id(),
                    new Scope.Org(new OrgId(org.id().value())));
            log.info("Bootstrapped owner '{}' (owner of org '{}' / {}). Sign into the console with it.",
                    email, orgName, org.id().value());
        } catch (RuntimeException e) {
            // Most likely a weak password (<12 chars). Don't block boot.
            log.error("Owner bootstrap failed ({}). The service is up but no master user was created. "
                    + "Fix yaya.identity.bootstrap.owner.* and restart.", e.getMessage());
        }
    }
}
