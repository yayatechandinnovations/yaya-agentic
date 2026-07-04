package com.yayatechandinnovations.yayaagentic.channel.chat.verify;

import com.yayatechandinnovations.yayaagentic.persistence.ChatAccountEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChatAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence + self-service for phone-verified chat accounts
 * ({@code chat-account-self-service-design.md} §5, §6). The account is keyed by a
 * stable UUID so a phone change is a one-row update that keeps history (sessions
 * join on the account id, not the phone). {@code passGen} is the revocation
 * generation: bump it and every outstanding device pass stops authenticating.
 */
@Service
public class ChatAccountService {

    private final ChatAccountRepository accounts;

    public ChatAccountService(ChatAccountRepository accounts) {
        this.accounts = accounts;
    }

    /** The verified identity to stamp on a freshly-minted device pass. */
    public record VerifiedAccount(String id, String phoneE164, String name, long passGen) {
        public static VerifiedAccount of(ChatAccountEntity a) {
            return new VerifiedAccount(a.getId().toString(), a.getPhoneE164(), a.getName(), a.getPassGen());
        }
    }

    /* ------------------------------ reads --------------------------------- */

    /** Does a completed (ACTIVE) registration already exist for this phone? Drives
     *  whether the app asks for a name before sending the OTP. */
    @Transactional(readOnly = true)
    public boolean isRegistered(String phoneE164) {
        return accounts.findActiveByPhone(phoneE164).isPresent();
    }

    /**
     * The ACTIVE account a device pass currently maps to, or empty if the pass has
     * been revoked (generation mismatch), the account was deleted, or the pass is a
     * stale legacy one whose phone no longer has an account. This is the single
     * trust check the request path applies to a verified device (§5.2, §11).
     */
    @Transactional(readOnly = true)
    public Optional<ChatAccountEntity> liveAccountFor(VerifiedDevice device) {
        if (device == null) return Optional.empty();
        // Legacy pass (minted before accounts were id-keyed): no acct/gen claim, so
        // revocation can't be checked — grandfather it, but still fail closed if the
        // phone's ACTIVE account is gone (a delete revokes even legacy passes).
        if (device.accountId() == null || device.passGen() == null) {
            return accounts.findActiveByPhone(device.phoneE164());
        }
        UUID id = parseId(device.accountId());
        if (id == null) return Optional.empty();
        return accounts.findById(id)
                .filter(ChatAccountEntity::isActive)
                .filter(a -> a.getPassGen() == device.passGen());
    }

    /** True when the device pass still authenticates (§5.2). */
    @Transactional(readOnly = true)
    public boolean isLive(VerifiedDevice device) {
        return liveAccountFor(device).isPresent();
    }

    /* --------------------------- verification ----------------------------- */

    /**
     * Records a successful verification and returns the account to stamp on the
     * device pass. First-time registration inserts a new id-keyed row; a returning
     * phone keeps its stored name unless a new non-blank one is supplied. Called
     * only after Twilio has approved the code.
     */
    @Transactional
    public VerifiedAccount completeVerification(String phoneE164, String suppliedName) {
        String clean = suppliedName == null || suppliedName.isBlank() ? null : suppliedName.trim();
        ChatAccountEntity account = accounts.findActiveByPhone(phoneE164).orElse(null);
        if (account == null) {
            account = accounts.save(new ChatAccountEntity(UUID.randomUUID(), phoneE164, clean));
            return VerifiedAccount.of(account);
        }
        if (clean != null && !clean.equals(account.getName())) {
            account.setName(clean);
            account = accounts.save(account);
        }
        return VerifiedAccount.of(account);
    }

    /* ---------------------------- mutations ------------------------------- */

    /** Rename the account (§6.1). No generation bump — a rename does not revoke
     *  passes; the caller re-mints a pass carrying the new name. */
    @Transactional
    public VerifiedAccount rename(UUID accountId, String name) {
        ChatAccountEntity a = requireActive(accountId);
        String clean = name == null || name.isBlank() ? null : name.trim();
        a.setName(clean);
        return VerifiedAccount.of(accounts.save(a));
    }

    /** Migrate the account's phone (§6.2, after both OTPs verify). One-row update:
     *  sessions join on the account id, so history follows. Bumps the generation so
     *  the old device's pass is revoked; the caller re-mints a fresh pass. */
    @Transactional
    public VerifiedAccount changePhone(UUID accountId, String newPhoneE164) {
        ChatAccountEntity a = requireActive(accountId);
        a.setPhoneE164(newPhoneE164);
        a.bumpPassGen();
        return VerifiedAccount.of(accounts.save(a));
    }

    /** Soft-delete + PII scrub + revoke all passes (§6.3). Hard-delete of the row
     *  happens later, after the GDPR grace window; conversation erasure is driven
     *  separately through the recorder. */
    @Transactional
    public void softDelete(UUID accountId) {
        ChatAccountEntity a = requireActive(accountId);
        a.setStatus(ChatAccountEntity.STATUS_DELETED);
        a.setName(null);
        a.setDeletedAt(OffsetDateTime.now());
        a.bumpPassGen();
        accounts.save(a);
    }

    /** Sign out of every device: bump the generation, revoking all outstanding
     *  passes without touching the account. */
    @Transactional
    public VerifiedAccount signOutEverywhere(UUID accountId) {
        ChatAccountEntity a = requireActive(accountId);
        a.bumpPassGen();
        return VerifiedAccount.of(accounts.save(a));
    }

    /* ------------------------------ helpers ------------------------------- */

    private ChatAccountEntity requireActive(UUID accountId) {
        return accounts.findById(accountId)
                .filter(ChatAccountEntity::isActive)
                .orElseThrow(() -> new IllegalStateException("no active chat account " + accountId));
    }

    private static UUID parseId(String s) {
        try {
            return UUID.fromString(s);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
