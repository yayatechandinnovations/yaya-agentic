package com.yayatechandinnovations.yayaidentity.credentials;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Lockout logic (design §11): locks after N failures, a success/reset clears it. */
class LoginAttemptServiceTest {

    @Test
    void locksAfterMaxAttempts() {
        LoginAttemptService svc = new LoginAttemptService(3, 15);
        assertThat(svc.isLocked("u@acme.test")).isFalse();
        svc.recordFailure("u@acme.test");
        svc.recordFailure("u@acme.test");
        assertThat(svc.isLocked("u@acme.test")).isFalse();   // 2 < 3
        svc.recordFailure("u@acme.test");
        assertThat(svc.isLocked("u@acme.test")).isTrue();    // 3 → locked
    }

    @Test
    void resetClearsLock() {
        LoginAttemptService svc = new LoginAttemptService(2, 15);
        svc.recordFailure("u@acme.test");
        svc.recordFailure("u@acme.test");
        assertThat(svc.isLocked("u@acme.test")).isTrue();
        svc.reset("u@acme.test");
        assertThat(svc.isLocked("u@acme.test")).isFalse();
    }

    @Test
    void differentKeysAreIndependent() {
        LoginAttemptService svc = new LoginAttemptService(1, 15);
        svc.recordFailure("a@acme.test");
        assertThat(svc.isLocked("a@acme.test")).isTrue();
        assertThat(svc.isLocked("b@acme.test")).isFalse();
    }

    @Test
    void expiredLockoutIsNoLongerLocked() {
        LoginAttemptService svc = new LoginAttemptService(1, 0);   // 0-minute window
        svc.recordFailure("u@acme.test");
        assertThat(svc.isLocked("u@acme.test")).isFalse();         // lockedUntil already in the past
    }
}
