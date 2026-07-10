package com.yayatechandinnovations.yayaidentity.signup;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SignupRateLimiterTest {

    @Test
    void capsSendsPerKeyWithinTheWindowAndRecoversAfterIt() {
        SignupRateLimiter limiter = new SignupRateLimiter(2, Duration.ofHours(1));
        Instant t0 = Instant.parse("2026-07-09T12:00:00Z");

        assertThat(limiter.tryAcquire("+1809", t0)).isTrue();
        assertThat(limiter.tryAcquire("+1809", t0.plusSeconds(10))).isTrue();
        assertThat(limiter.tryAcquire("+1809", t0.plusSeconds(20))).isFalse();
        // A different key has its own budget.
        assertThat(limiter.tryAcquire("+1829", t0.plusSeconds(20))).isTrue();
        // The window slides: the first send ages out.
        assertThat(limiter.tryAcquire("+1809", t0.plus(Duration.ofMinutes(61)))).isTrue();
    }

    @Test
    void blankKeysAreNotLimited() {
        SignupRateLimiter limiter = new SignupRateLimiter(1, Duration.ofHours(1));
        Instant t0 = Instant.parse("2026-07-09T12:00:00Z");
        assertThat(limiter.tryAcquire(null, t0)).isTrue();
        assertThat(limiter.tryAcquire(null, t0)).isTrue();
        assertThat(limiter.tryAcquire("", t0)).isTrue();
    }
}
