package com.yayatechandinnovations.yayaidentity.identity;

import com.yayatechandinnovations.yayaidentity.credentials.CredentialService;
import com.yayatechandinnovations.yayaidentity.organizations.InvitationService;
import com.yayatechandinnovations.yayaidentity.organizations.Organization;
import com.yayatechandinnovations.yayaidentity.organizations.OrganizationService;
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

import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Org onboarding is invite-gated (US-20..US-22, AC-10): invite → accept in a
 * single transaction yields an ACTIVE user, a working password, and a membership.
 */
@SpringBootTest
@Testcontainers
class InvitationFlowIT {

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

    @Autowired OrganizationService orgs;
    @Autowired UserService users;
    @Autowired CredentialService credentials;
    @Autowired InvitationService invitations;

    @Test
    void inviteThenAcceptActivatesUserAndMembership() {
        Organization org = orgs.create("Invite Org");
        var invite = invitations.invite(org.id(), "newbie@acme.test", "admin", "owner-id",
                Duration.ofDays(7));

        var result = invitations.accept(invite.token(), "Newbie", "correct-horse-battery");

        User user = users.byId(result.user()).orElseThrow();
        assertThat(user.status()).isEqualTo(User.Status.ACTIVE);
        assertThat(credentials.verifyPassword(user.id(), "correct-horse-battery")).isTrue();
        assertThat(orgs.membersOf(org.id())).hasSize(1);
    }

    @Test
    void tokenIsSingleUse() {
        Organization org = orgs.create("Single Use Org");
        var invite = invitations.invite(org.id(), "once@acme.test", null, null, Duration.ofDays(7));
        invitations.accept(invite.token(), "Once", "correct-horse-battery");

        assertThatThrownBy(() -> invitations.accept(invite.token(), "Again", "correct-horse-battery"))
                .isInstanceOf(InvitationService.InvalidInvitationException.class);
    }

    @Test
    void unknownTokenRejected() {
        assertThatThrownBy(() -> invitations.accept("not-a-real-token", "X", "correct-horse-battery"))
                .isInstanceOf(InvitationService.InvalidInvitationException.class);
    }

    @Test
    void expiredInvitationRejected() throws InterruptedException {
        Organization org = orgs.create("Expired Org");
        var invite = invitations.invite(org.id(), "late@acme.test", null, null, Duration.ofMillis(1));
        Thread.sleep(30);
        assertThatThrownBy(() -> invitations.accept(invite.token(), "Late", "correct-horse-battery"))
                .isInstanceOf(InvitationService.InvalidInvitationException.class);
    }
}
