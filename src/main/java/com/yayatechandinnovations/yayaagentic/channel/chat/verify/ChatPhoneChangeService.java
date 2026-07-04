package com.yayatechandinnovations.yayaagentic.channel.chat.verify;

import com.yayatechandinnovations.yayaagentic.api.ChatApiException;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatRateLimiter;
import com.yayatechandinnovations.yayaagentic.persistence.ChatPhoneChangeEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChatPhoneChangeRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The dual-OTP phone-change ceremony ({@code chat-account-self-service-design.md}
 * §6.2). Prove control of the CURRENT number first (the "right person" gate),
 * then the NEW number, then migrate. Because sessions join on the account id, the
 * migration is a one-row update that keeps history; it bumps the pass generation,
 * revoking the old device's pass.
 *
 * <p>Every OTP send is gated by the same toll-fraud ceilings as {@code /verify/*}
 * (§20.4) — applied here, at the exact send point, so a failed step can't be used
 * to pump SMS.
 */
@Service
public class ChatPhoneChangeService {

    private static final Duration HOUR = Duration.ofHours(1);
    private static final Duration DAY = Duration.ofHours(24);
    private static final Duration CEREMONY_TTL = Duration.ofMinutes(15);

    // Mirror the §20.4 ceilings enforced by ChatVerifyController.
    private static final int START_PER_IP_PER_HOUR = 5;
    private static final int START_PER_PHONE_PER_DAY = 5;
    private static final int START_GLOBAL_PER_DAY = 5000;
    private static final int CHECK_PER_PHONE_PER_HOUR = 10;
    private static final int MAX_ATTEMPTS = 5;

    private final PhoneVerifier verifier;
    private final ChatRateLimiter rateLimiter;
    private final ChatAccountService accounts;
    private final ChatPhoneChangeRepository changes;

    public ChatPhoneChangeService(PhoneVerifier verifier,
                                  ChatRateLimiter rateLimiter,
                                  ChatAccountService accounts,
                                  ChatPhoneChangeRepository changes) {
        this.verifier = verifier;
        this.rateLimiter = rateLimiter;
        this.accounts = accounts;
        this.changes = changes;
    }

    /** Step 1: OTP to the CURRENT number (§6.2). Aborts any prior in-flight change. */
    @Transactional
    public ChatPhoneChangeEntity start(UUID accountId, String oldPhone, String newPhone, String clientIp) {
        if (newPhone.equals(oldPhone)) {
            throw badRequest("that is already your number");
        }
        // Reject a target already owned by an ACTIVE account. Bounded existence
        // reveal to an authenticated caller (§11); avoids SMS-spamming others.
        if (accounts.isRegistered(newPhone)) {
            throw new ChatApiException(HttpStatus.CONFLICT, "phone_unavailable",
                    "that number can't be used", 0);
        }
        // One in-flight change per account.
        for (ChatPhoneChangeEntity active : inFlight(accountId)) {
            active.setState(ChatPhoneChangeEntity.ABORTED);
            changes.save(active);
        }
        startOtp(oldPhone, clientIp);
        return changes.save(new ChatPhoneChangeEntity(
                UUID.randomUUID(), accountId, oldPhone, newPhone,
                OffsetDateTime.now().plus(CEREMONY_TTL)));
    }

    /** Step 2: verify the OLD-number code; on success, OTP to the NEW number. */
    @Transactional
    public void verifyOld(UUID accountId, UUID changeId, String code, String clientIp) {
        ChatPhoneChangeEntity c = require(accountId, changeId, ChatPhoneChangeEntity.OLD_PENDING);
        checkCode(c, c.getOldPhone(), code);
        c.markOldVerified();
        c.setState(ChatPhoneChangeEntity.NEW_PENDING);
        changes.save(c);
        startOtp(c.getNewPhone(), clientIp);
    }

    /** Step 3: verify the NEW-number code, then migrate the account. Returns the
     *  updated account so the caller can re-mint a fresh device pass. */
    @Transactional
    public ChatAccountService.VerifiedAccount verifyNew(UUID accountId, UUID changeId, String code) {
        ChatPhoneChangeEntity c = require(accountId, changeId, ChatPhoneChangeEntity.NEW_PENDING);
        checkCode(c, c.getNewPhone(), code);
        // Race guard: the number could have been claimed since step 1.
        if (accounts.isRegistered(c.getNewPhone())) {
            c.setState(ChatPhoneChangeEntity.ABORTED);
            changes.save(c);
            throw new ChatApiException(HttpStatus.CONFLICT, "phone_unavailable",
                    "that number can't be used", 0);
        }
        c.setState(ChatPhoneChangeEntity.COMPLETE);
        changes.save(c);
        return accounts.changePhone(accountId, c.getNewPhone());
    }

    /** Resend the current step's OTP. */
    @Transactional
    public void resend(UUID accountId, UUID changeId, String clientIp) {
        ChatPhoneChangeEntity c = loadOwned(accountId, changeId);
        String target = switch (c.getState()) {
            case ChatPhoneChangeEntity.OLD_PENDING -> c.getOldPhone();
            case ChatPhoneChangeEntity.NEW_PENDING -> c.getNewPhone();
            default -> throw badRequest("this change is no longer active");
        };
        if (c.isExpired()) throw expired(c);
        startOtp(target, clientIp);
    }

    @Transactional
    public void abort(UUID accountId, UUID changeId) {
        ChatPhoneChangeEntity c = loadOwned(accountId, changeId);
        c.setState(ChatPhoneChangeEntity.ABORTED);
        changes.save(c);
    }

    /* ------------------------------ internals ----------------------------- */

    private List<ChatPhoneChangeEntity> inFlight(UUID accountId) {
        return changes.findByAccountIdAndStateIn(accountId,
                List.of(ChatPhoneChangeEntity.OLD_PENDING, ChatPhoneChangeEntity.NEW_PENDING));
    }

    private ChatPhoneChangeEntity require(UUID accountId, UUID changeId, String expectedState) {
        ChatPhoneChangeEntity c = loadOwned(accountId, changeId);
        if (!expectedState.equals(c.getState())) throw badRequest("unexpected step for this change");
        if (c.isExpired()) throw expired(c);
        return c;
    }

    private ChatPhoneChangeEntity loadOwned(UUID accountId, UUID changeId) {
        ChatPhoneChangeEntity c = changes.findById(changeId)
                .orElseThrow(() -> new ChatApiException(HttpStatus.NOT_FOUND, "not_found",
                        "no such phone change", 0));
        // Existence hidden across accounts: a mismatched owner reads as "not found".
        if (!c.getAccountId().equals(accountId)) {
            throw new ChatApiException(HttpStatus.NOT_FOUND, "not_found", "no such phone change", 0);
        }
        return c;
    }

    private void checkCode(ChatPhoneChangeEntity c, String phone, String code) {
        if (code == null || code.isBlank()) throw badRequest("code required");
        requireWithinLimit(rateLimiter.hit("verify-check-phone", phone, CHECK_PER_PHONE_PER_HOUR, HOUR));
        if (!verifier.check(phone, code.trim()).approved()) {
            if (c.bumpAttempts() >= MAX_ATTEMPTS) {
                c.setState(ChatPhoneChangeEntity.ABORTED);
            }
            changes.save(c);
            throw new ChatApiException(HttpStatus.UNAUTHORIZED, "verify_denied",
                    "that code is invalid or expired", 0);
        }
    }

    private void startOtp(String phone, String clientIp) {
        requireWithinLimit(rateLimiter.hit("verify-global", "all", START_GLOBAL_PER_DAY, DAY));
        requireWithinLimit(rateLimiter.hit("verify-start-ip", clientIp, START_PER_IP_PER_HOUR, HOUR));
        requireWithinLimit(rateLimiter.hit("verify-start-phone", phone, START_PER_PHONE_PER_DAY, DAY));
        if (!verifier.start(phone).ok()) {
            throw new ChatApiException(HttpStatus.BAD_GATEWAY, "verify_failed",
                    "could not send a verification code", 0);
        }
    }

    private void requireWithinLimit(ChatRateLimiter.Decision d) {
        if (!d.allowed()) throw ChatApiException.rateLimited(d.retryAfterSeconds());
    }

    private static ChatApiException expired(ChatPhoneChangeEntity c) {
        return new ChatApiException(HttpStatus.GONE, "change_expired",
                "this phone change expired — start over", 0);
    }

    private static ChatApiException badRequest(String message) {
        return new ChatApiException(HttpStatus.BAD_REQUEST, "bad_request", message, 0);
    }
}
