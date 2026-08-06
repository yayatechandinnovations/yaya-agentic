package com.yayatechandinnovations.yayaagentic.channel.chat.verify;

import com.yayatechandinnovations.yayaagentic.persistence.ChatAccountEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChatAccountRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Account-level PIN with server-side lockout ({@code chat-experience-design.md}
 * §5). The PIN is a convenience factor strictly below OTP: it can never change
 * the phone, delete the account, or remove itself without the current PIN in
 * hand — and a hard lockout always resolves through a fresh OTP, which resets
 * the counters.
 *
 * <p>Lockout ladder (counters live on the ACCOUNT, not the device, so rotating
 * devices doesn't reset them): 5 consecutive failures &rarr; 15 min lock; 10
 * &rarr; 1 h; 15+ &rarr; hard-locked pending OTP.
 */
@Service
public class ChatPinService {

    private static final Pattern PIN = Pattern.compile("^[0-9]{4,6}$");
    static final int SOFT_LOCK_ATTEMPTS = 5;
    static final int HOUR_LOCK_ATTEMPTS = 10;
    static final int HARD_LOCK_ATTEMPTS = 15;
    static final Duration SOFT_LOCK = Duration.ofMinutes(15);
    static final Duration HOUR_LOCK = Duration.ofHours(1);

    private final ChatAccountRepository accounts;
    private final PasswordEncoder encoder;
    private final Clock clock;

    public ChatPinService(ChatAccountRepository accounts, PasswordEncoder encoder, Clock clock) {
        this.accounts = accounts;
        this.encoder = encoder;
        this.clock = clock;
    }

    public sealed interface PinResult permits Ok, Denied {}
    public record Ok() implements PinResult {}
    /** Uniform deny — callers surface it as one indistinguishable 401. The
     *  {@code otpRequired} flag is the only distinction the app may show: the
     *  ladder is exhausted and only a fresh OTP unlocks. */
    public record Denied(boolean otpRequired) implements PinResult {}

    public static boolean validFormat(String pin) {
        return pin != null && PIN.matcher(pin.trim()).matches();
    }

    /* ------------------------------ set/change ----------------------------- */

    /**
     * Set or change the PIN. When the account already has one, the current PIN
     * must be presented (an attacker holding an unlocked phone with a valid pass
     * must not be able to swap the PIN under the owner). First-time set needs
     * only the pass — the OTP that minted it is the stronger proof.
     */
    @Transactional
    public PinResult setPin(UUID accountId, String currentPin, String newPin) {
        ChatAccountEntity a = accounts.findById(accountId)
                .filter(ChatAccountEntity::isActive)
                .orElseThrow(() -> new IllegalStateException("no active chat account " + accountId));
        if (a.hasPin()) {
            PinResult gate = verifyAgainst(a, currentPin);
            if (gate instanceof Denied) return gate;
        }
        a.setPin(encoder.encode(newPin.trim()));
        accounts.save(a);
        return new Ok();
    }

    /** Remove the PIN (restores long-TTL passes). Requires the current PIN —
     *  the weaker factor may not be stripped by someone who doesn't know it. */
    @Transactional
    public PinResult removePin(UUID accountId, String currentPin) {
        ChatAccountEntity a = accounts.findById(accountId)
                .filter(ChatAccountEntity::isActive)
                .orElseThrow(() -> new IllegalStateException("no active chat account " + accountId));
        if (!a.hasPin()) return new Ok();
        PinResult gate = verifyAgainst(a, currentPin);
        if (gate instanceof Denied) return gate;
        a.setPin(null);
        accounts.save(a);
        return new Ok();
    }

    /* ------------------------------ verify --------------------------------- */

    /** Verify a PIN attempt under the lockout ladder, persisting counters. */
    @Transactional
    public PinResult verify(UUID accountId, String pin) {
        ChatAccountEntity a = accounts.findById(accountId)
                .filter(ChatAccountEntity::isActive)
                .orElse(null);
        if (a == null || !a.hasPin()) return new Denied(false);
        PinResult result = verifyAgainst(a, pin);
        accounts.save(a);
        return result;
    }

    /* ------------------------------ internals ------------------------------ */

    /** Mutates the entity's counters; the caller persists. */
    private PinResult verifyAgainst(ChatAccountEntity a, String pin) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (a.getPinFailedAttempts() >= HARD_LOCK_ATTEMPTS) {
            return new Denied(true);
        }
        if (a.getPinLockedUntil() != null && a.getPinLockedUntil().isAfter(now)) {
            return new Denied(false);
        }
        if (validFormat(pin) && encoder.matches(pin.trim(), a.getPinHash())) {
            a.resetPinCounters();
            return new Ok();
        }
        int attempts = a.getPinFailedAttempts() + 1;
        a.setPinFailedAttempts(attempts);
        if (attempts >= HARD_LOCK_ATTEMPTS) {
            return new Denied(true);
        }
        if (attempts >= HOUR_LOCK_ATTEMPTS) {
            a.setPinLockedUntil(now.plus(HOUR_LOCK));
        } else if (attempts >= SOFT_LOCK_ATTEMPTS) {
            a.setPinLockedUntil(now.plus(SOFT_LOCK));
        }
        return new Denied(false);
    }
}
