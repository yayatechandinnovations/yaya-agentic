package com.yayatechandinnovations.yayaidentity.credentials;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Brute-force lockout for interactive login (design §11). After N consecutive
 * failures a key (the submitted username) is locked for a cooling-off window.
 * In-memory per instance in v1 — a shared Redis store is the multi-instance
 * upgrade, behind the same interface.
 */
@Service
public class LoginAttemptService {

    private final int maxAttempts;
    private final Duration lockout;
    private final ConcurrentMap<String, State> states = new ConcurrentHashMap<>();

    public LoginAttemptService(@Value("${yaya.identity.login.max-attempts:5}") int maxAttempts,
                               @Value("${yaya.identity.login.lockout-minutes:15}") long lockoutMinutes) {
        this.maxAttempts = maxAttempts;
        this.lockout = Duration.ofMinutes(lockoutMinutes);
    }

    public void recordFailure(String key) {
        if (key == null || key.isBlank()) return;
        states.compute(key, (k, s) -> {
            State state = s == null ? new State() : s;
            state.count++;
            if (state.count >= maxAttempts) {
                state.lockedUntil = Instant.now().plus(lockout);
            }
            return state;
        });
    }

    public boolean isLocked(String key) {
        if (key == null) return false;
        State s = states.get(key);
        return s != null && s.lockedUntil != null && Instant.now().isBefore(s.lockedUntil);
    }

    public void reset(String key) {
        if (key != null) states.remove(key);
    }

    private static final class State {
        int count;
        Instant lockedUntil;
    }
}
