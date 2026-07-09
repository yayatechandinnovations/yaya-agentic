package com.yayatechandinnovations.yayasales.platform.concurrency;

import jakarta.persistence.OptimisticLockException;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Compare-and-set retry for version-guarded aggregates (design §5.2, §5.7).
 * Losers of a concurrent write (stock summary, order, slot) retry with a small
 * jittered backoff; exhaustion surfaces as {@link ConflictedException} — a
 * visible conflict, never silent corruption.
 *
 * <p>The supplier MUST be a full re-read-and-write unit (each attempt re-reads
 * the row at its current version); retrying a stale in-memory entity would just
 * lose again.
 */
public final class OptimisticRetry {

    private static final int DEFAULT_ATTEMPTS = 3;

    private OptimisticRetry() {}

    public static <T> T withRetries(Supplier<T> unit) {
        return withRetries(DEFAULT_ATTEMPTS, unit);
    }

    public static <T> T withRetries(int attempts, Supplier<T> unit) {
        if (attempts < 1) throw new IllegalArgumentException("attempts must be >= 1");
        OptimisticLockingFailureException last = null;
        for (int i = 0; i < attempts; i++) {
            try {
                return unit.get();
            } catch (OptimisticLockingFailureException e) {
                last = e;
            } catch (OptimisticLockException e) {
                last = new OptimisticLockingFailureException(e.getMessage(), e);
            }
            backoff(i);
        }
        throw new ConflictedException(attempts, last);
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(5, 25L * (attempt + 1)));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ConflictedException(attempt + 1, null);
        }
    }

    public static class ConflictedException extends RuntimeException {
        public ConflictedException(int attempts, Throwable cause) {
            super("optimistic write lost " + attempts + " attempts — concurrent modification", cause);
        }
    }
}
