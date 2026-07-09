package com.yayatechandinnovations.yayaidentity.credentials;

import com.yayatechandinnovations.yayaidentity.organizations.OrganizationService;
import com.yayatechandinnovations.yayaidentity.roles.AssignmentSubject;
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

import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TOTP MFA (design §6.2/§11): enrol, verify a computed code, and the per-org MFA
 * policy — a privileged user in an MFA-required org must enrol before acting.
 */
@SpringBootTest
@Testcontainers
class TotpMfaIT {

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

    @Autowired UserService users;
    @Autowired CredentialService credentials;
    @Autowired OrganizationService orgs;
    @Autowired RoleService roles;
    @Autowired RoleAssignmentService assignments;
    @Autowired MfaPolicyService mfa;

    @Test
    void enrolAndVerifyTotp() {
        User u = users.createPerson("totp@acme.test", null, "U");
        String secret = credentials.enrollTotp(u.id());
        assertThat(credentials.hasTotp(u.id())).isTrue();

        String validCode = Totp.code(Totp.fromBase32(secret), Instant.now().getEpochSecond());
        assertThat(credentials.verifyTotp(u.id(), validCode)).isTrue();
        assertThat(credentials.verifyTotp(u.id(), "000000")).isFalse();
    }

    @Test
    void mfaRequiredForPrivilegedRoleUntilEnrolled() {
        var org = orgs.create("MFA Org");
        User admin = users.createPerson("admin@mfa.test", null, "Admin");
        orgs.addMember(org.id(), admin.id());
        Role adminRole = roles.systemRole("admin").orElseThrow();
        assignments.assign(org.id(), AssignmentSubject.user(admin.id()), adminRole.id(),
                new Scope.Org(org.id()));

        // No MFA policy yet → not required.
        assertThat(mfa.requiresMfa(admin.id(), org.id())).isFalse();

        orgs.setMfaRequired(org.id(), true);
        assertThat(mfa.requiresMfa(admin.id(), org.id())).isTrue();
        assertThat(mfa.mfaEnrollmentRequired(admin.id(), org.id())).isTrue();

        credentials.enrollTotp(admin.id());
        assertThat(mfa.mfaEnrollmentRequired(admin.id(), org.id())).isFalse();
    }

    @Test
    void mfaNotRequiredForNonPrivilegedUser() {
        var org = orgs.create("MFA Org 2");
        User member = users.createPerson("member@mfa.test", null, "Member");
        orgs.addMember(org.id(), member.id());
        orgs.setMfaRequired(org.id(), true);
        // Holds no privileged role → policy doesn't apply.
        assertThat(mfa.requiresMfa(member.id(), org.id())).isFalse();
    }
}
