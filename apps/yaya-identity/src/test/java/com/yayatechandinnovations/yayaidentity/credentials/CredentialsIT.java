package com.yayatechandinnovations.yayaidentity.credentials;

import com.yayatechandinnovations.yayaidentity.platform.Ids.UserId;
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
 * I1c credential mechanics (design §6.2): password (Argon2id, one active,
 * resettable) and phone OTP (single-use, attempt-limited, expiring). OTP TTL is
 * squeezed to 1s here so expiry is exercised without a long wait.
 */
@SpringBootTest
@Testcontainers
class CredentialsIT {

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
        r.add("yaya.identity.otp.ttl-seconds", () -> "1");
        r.add("yaya.identity.otp.max-attempts", () -> "3");
    }

    @Autowired UserService users;
    @Autowired CredentialService credentials;

    private UserId newUser() {
        User u = users.createPerson("u" + System.nanoTime() + "@acme.test", null, "U");
        return u.id();
    }

    @Test
    void passwordSetVerifyAndReset() {
        UserId u = newUser();
        credentials.setPassword(u, "correct-horse-battery");
        assertThat(credentials.verifyPassword(u, "correct-horse-battery")).isTrue();
        assertThat(credentials.verifyPassword(u, "wrong")).isFalse();

        // Reset: the old active password is revoked, the new one works (one-active invariant holds).
        credentials.setPassword(u, "new-correct-horse-99");
        assertThat(credentials.verifyPassword(u, "correct-horse-battery")).isFalse();
        assertThat(credentials.verifyPassword(u, "new-correct-horse-99")).isTrue();
    }

    @Test
    void weakPasswordRejected() {
        UserId u = newUser();
        assertThatThrownBy(() -> credentials.setPassword(u, "short"))
                .isInstanceOf(CredentialService.WeakPasswordException.class);
    }

    @Test
    void otpVerifiesOnceThenIsConsumed() {
        UserId u = newUser();
        String code = credentials.issueOtp(u);
        assertThat(credentials.verifyOtp(u, code)).isTrue();
        assertThat(credentials.verifyOtp(u, code)).isFalse();   // single-use
    }

    @Test
    void otpLocksOutAfterMaxAttempts() {
        UserId u = newUser();
        String code = credentials.issueOtp(u);
        for (int i = 0; i < 3; i++) {
            assertThat(credentials.verifyOtp(u, "000000")).isFalse();
        }
        // Even the correct code fails once locked out.
        assertThat(credentials.verifyOtp(u, code)).isFalse();
    }

    @Test
    void otpExpires() throws InterruptedException {
        UserId u = newUser();
        String code = credentials.issueOtp(u);
        Thread.sleep(1200);   // TTL is 1s
        assertThat(credentials.verifyOtp(u, code)).isFalse();
    }
}
