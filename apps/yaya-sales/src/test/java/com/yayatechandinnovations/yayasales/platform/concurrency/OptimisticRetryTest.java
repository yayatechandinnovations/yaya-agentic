package com.yayatechandinnovations.yayasales.platform.concurrency;

import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OptimisticRetryTest {

    @Test
    void retriesUntilTheWriteWins() {
        AtomicInteger attempts = new AtomicInteger();

        String result = OptimisticRetry.withRetries(3, () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new OptimisticLockingFailureException("lost the CAS");
            }
            return "won";
        });

        assertThat(result).isEqualTo("won");
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void exhaustionSurfacesAsConflictNotSilence() {
        assertThatThrownBy(() -> OptimisticRetry.withRetries(2, () -> {
            throw new OptimisticLockingFailureException("always loses");
        })).isInstanceOf(OptimisticRetry.ConflictedException.class);
    }

    @Test
    void nonConcurrencyFailuresAreNotRetried() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> OptimisticRetry.withRetries(3, () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("a real bug");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(attempts.get()).isEqualTo(1);
    }
}
