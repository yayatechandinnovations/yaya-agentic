package com.yayatechandinnovations.yayaidentity.signup;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window cap on OTP sends. This is a COST control as much as an abuse
 * control: with Twilio Verify every start() is billable SMS, and "SMS pumping"
 * against an open registration endpoint is a well-known fraud pattern.
 * In-memory on purpose — a restart forgiving the window is acceptable, a paid
 * dependency to track it is not.
 */
public class SignupRateLimiter {

    private final int maxPerWindow;
    private final Duration window;
    private final Map<String, Deque<Instant>> events = new ConcurrentHashMap<>();

    public SignupRateLimiter(int maxPerWindow, Duration window) {
        this.maxPerWindow = maxPerWindow;
        this.window = window;
    }

    public boolean tryAcquire(String key, Instant now) {
        if (key == null || key.isBlank()) {
            return true;
        }
        Deque<Instant> deque = events.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            Instant cutoff = now.minus(window);
            while (!deque.isEmpty() && deque.peekFirst().isBefore(cutoff)) {
                deque.pollFirst();
            }
            if (deque.size() >= maxPerWindow) {
                return false;
            }
            deque.addLast(now);
            return true;
        }
    }
}
