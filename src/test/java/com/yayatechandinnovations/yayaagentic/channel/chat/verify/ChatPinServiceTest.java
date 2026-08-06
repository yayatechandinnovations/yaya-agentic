package com.yayatechandinnovations.yayaagentic.channel.chat.verify;

import com.yayatechandinnovations.yayaagentic.persistence.ChatAccountEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChatAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Locks in the PIN lockout ladder ({@code chat-experience-design.md} §5.4):
 * 5 consecutive failures &rarr; 15 min; 10 &rarr; 1 h; 15 &rarr; OTP-only. A
 * fresh OTP (completeVerification) resets the ladder; a correct PIN resets it
 * too. Counters live on the account so device rotation can't dodge them.
 */
class ChatPinServiceTest {

    /** Deterministic, cheap stand-in for Argon2 — the ladder, not the KDF, is under test. */
    private static final PasswordEncoder PLAIN = new PasswordEncoder() {
        @Override public String encode(CharSequence raw) { return "enc:" + raw; }
        @Override public boolean matches(CharSequence raw, String encoded) {
            return ("enc:" + raw).equals(encoded);
        }
    };

    private final ChatAccountRepository repo = mock(ChatAccountRepository.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-08-06T12:00:00Z"));
    private final ChatPinService svc = new ChatPinService(repo, PLAIN, clock);

    private ChatAccountEntity account(UUID id) {
        ChatAccountEntity a = new ChatAccountEntity(id, "+18095551234", "Ada");
        when(repo.findById(id)).thenReturn(Optional.of(a));
        when(repo.save(any(ChatAccountEntity.class))).thenAnswer(i -> i.getArgument(0));
        return a;
    }

    @Test
    void firstTimeSetNeedsNoCurrentPin() {
        UUID id = UUID.randomUUID();
        ChatAccountEntity a = account(id);
        assertThat(svc.setPin(id, null, "1234")).isInstanceOf(ChatPinService.Ok.class);
        assertThat(a.hasPin()).isTrue();
        assertThat(svc.verify(id, "1234")).isInstanceOf(ChatPinService.Ok.class);
    }

    @Test
    void changeRequiresCurrentPin() {
        UUID id = UUID.randomUUID();
        account(id);
        svc.setPin(id, null, "1234");
        assertThat(svc.setPin(id, "9999", "5678")).isInstanceOf(ChatPinService.Denied.class);
        assertThat(svc.verify(id, "1234")).isInstanceOf(ChatPinService.Ok.class);
        assertThat(svc.setPin(id, "1234", "5678")).isInstanceOf(ChatPinService.Ok.class);
        assertThat(svc.verify(id, "5678")).isInstanceOf(ChatPinService.Ok.class);
    }

    @Test
    void removeRequiresCurrentPin() {
        UUID id = UUID.randomUUID();
        ChatAccountEntity a = account(id);
        svc.setPin(id, null, "1234");
        assertThat(svc.removePin(id, "0000")).isInstanceOf(ChatPinService.Denied.class);
        assertThat(a.hasPin()).isTrue();
        assertThat(svc.removePin(id, "1234")).isInstanceOf(ChatPinService.Ok.class);
        assertThat(a.hasPin()).isFalse();
    }

    @Test
    void fiveFailuresSoftLockAndTimePassesUnlocks() {
        UUID id = UUID.randomUUID();
        account(id);
        svc.setPin(id, null, "1234");
        for (int i = 0; i < 5; i++) {
            assertThat(svc.verify(id, "0000")).isInstanceOf(ChatPinService.Denied.class);
        }
        // Locked: even the RIGHT pin is denied inside the window.
        assertThat(svc.verify(id, "1234")).isInstanceOf(ChatPinService.Denied.class);
        clock.advance(Duration.ofMinutes(16));
        assertThat(svc.verify(id, "1234")).isInstanceOf(ChatPinService.Ok.class);
    }

    @Test
    void fifteenFailuresHardLockRequiresOtp() {
        UUID id = UUID.randomUUID();
        ChatAccountEntity a = account(id);
        svc.setPin(id, null, "1234");
        for (int i = 0; i < 15; i++) {
            clock.advance(Duration.ofHours(2)); // step over interim locks
            svc.verify(id, "0000");
        }
        clock.advance(Duration.ofDays(365));
        ChatPinService.PinResult result = svc.verify(id, "1234");
        assertThat(result).isInstanceOf(ChatPinService.Denied.class);
        assertThat(((ChatPinService.Denied) result).otpRequired()).isTrue();

        // The OTP path (completeVerification -> resetPinCounters) reopens it.
        a.resetPinCounters();
        assertThat(svc.verify(id, "1234")).isInstanceOf(ChatPinService.Ok.class);
    }

    @Test
    void successResetsTheLadder() {
        UUID id = UUID.randomUUID();
        ChatAccountEntity a = account(id);
        svc.setPin(id, null, "1234");
        for (int i = 0; i < 4; i++) svc.verify(id, "0000");
        assertThat(svc.verify(id, "1234")).isInstanceOf(ChatPinService.Ok.class);
        assertThat(a.getPinFailedAttempts()).isZero();
    }

    @Test
    void malformedPinCountsAsFailure() {
        UUID id = UUID.randomUUID();
        account(id);
        svc.setPin(id, null, "1234");
        assertThat(svc.verify(id, "not-a-pin")).isInstanceOf(ChatPinService.Denied.class);
        assertThat(svc.verify(id, null)).isInstanceOf(ChatPinService.Denied.class);
    }

    @Test
    void accountWithoutPinDeniesVerify() {
        UUID id = UUID.randomUUID();
        account(id);
        assertThat(svc.verify(id, "1234")).isInstanceOf(ChatPinService.Denied.class);
    }

    /** Minimal mutable clock — java.time has no built-in advancing fixed clock. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) { this.now = now; }
        void advance(Duration d) { now = now.plus(d); }

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
