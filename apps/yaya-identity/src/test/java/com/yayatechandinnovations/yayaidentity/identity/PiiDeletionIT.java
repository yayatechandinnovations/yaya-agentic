package com.yayatechandinnovations.yayaidentity.identity;

import com.yayatechandinnovations.yayaidentity.credentials.CredentialService;
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

/**
 * PII deletion (AC-44): scrub the user's PII and revoke credentials, but keep the
 * row + id so audit references stay valid.
 */
@SpringBootTest
@Testcontainers
class PiiDeletionIT {

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

    @Test
    void deleteScrubsPiiRevokesCredentialsButKeepsId() {
        User u = users.createPerson("gdpr@acme.test", "+1809555000", "To Delete");
        credentials.setPassword(u.id(), "correct-horse-battery");

        users.deleteUser(u.id());

        // Id survives; PII is gone; email is freed for re-signup.
        User after = users.byId(u.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(User.Status.DELETED);
        assertThat(after.primaryEmail()).isNull();
        assertThat(after.phone()).isNull();
        assertThat(after.displayName()).isNull();
        assertThat(users.byEmail("gdpr@acme.test")).isEmpty();
        assertThat(credentials.verifyPassword(u.id(), "correct-horse-battery")).isFalse();
    }
}
