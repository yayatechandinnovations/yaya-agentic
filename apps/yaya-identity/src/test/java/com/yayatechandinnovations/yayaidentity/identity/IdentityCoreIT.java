package com.yayatechandinnovations.yayaidentity.identity;

import com.yayatechandinnovations.yayaidentity.organizations.Membership;
import com.yayatechandinnovations.yayaidentity.organizations.Organization;
import com.yayatechandinnovations.yayaidentity.organizations.OrganizationService;
import com.yayatechandinnovations.yayaidentity.platform.outbox.OutboxRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * I1b: the identity core — create an org, create a user, tie them with a
 * membership, and confirm outbox events fire and email uniqueness holds
 * (US-20..US-25, FR-2.1..2.5).
 */
@SpringBootTest
@Testcontainers
class IdentityCoreIT {

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
    @Autowired UserService userSvc;
    @Autowired OutboxRepository outbox;

    @Test
    void createsOrgUserAndMembershipWithEvents() {
        long eventsBefore = outbox.count();

        Organization org = orgs.create("Acme Retail");
        assertThat(org.slug()).isEqualTo("acme-retail");
        assertThat(org.status()).isEqualTo(Organization.Status.ACTIVE);

        User user = userSvc.createPerson("owner@acme.test", null, "Owner");
        assertThat(user.status()).isEqualTo(User.Status.INVITED);   // no credential yet (I1c)
        assertThat(user.kind()).isEqualTo(User.Kind.PERSON);

        Membership m = orgs.addMember(org.id(), user.id());
        assertThat(m.status()).isEqualTo(Membership.Status.ACTIVE);

        assertThat(orgs.membersOf(org.id())).hasSize(1);
        assertThat(orgs.orgsOf(user.id())).hasSize(1);

        // organization.created + user.created + membership.added
        assertThat(outbox.count()).isEqualTo(eventsBefore + 3);
    }

    @Test
    void slugsAreDeduplicated() {
        Organization a = orgs.create("Duplicate Name");
        Organization b = orgs.create("Duplicate Name");
        assertThat(a.slug()).isNotEqualTo(b.slug());
        assertThat(b.slug()).startsWith("duplicate-name-");
    }

    @Test
    void emailUniquenessIsCaseInsensitive() {
        userSvc.createPerson("dup@acme.test", null, "First");
        assertThatThrownBy(() -> userSvc.createPerson("DUP@acme.test", null, "Second"))
                .isInstanceOf(UserService.EmailAlreadyInUseException.class);
    }

    @Test
    void serviceAccountsAreActiveImmediately() {
        User svc = userSvc.createServiceAccount("yaya-agentic-runtime");
        assertThat(svc.kind()).isEqualTo(User.Kind.SERVICE);
        assertThat(svc.canAuthenticate()).isTrue();
    }
}
