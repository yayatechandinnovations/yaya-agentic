package com.yayatechandinnovations.yayaidentity.credentials;

import com.yayatechandinnovations.yayaidentity.platform.Ids.UserId;
import com.yayatechandinnovations.yayaidentity.platform.crypto.SecretCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Credential enrolment + verification (design §6.2). Passwords are Argon2id
 * hashed with at most one ACTIVE per user; phone OTPs are short-lived,
 * single-use, hashed (never stored raw), and locked out after too many attempts.
 */
@Service
public class CredentialService {

    private final CredentialRepository repo;
    private final PasswordEncoder encoder;
    private final SecretCipher cipher;
    private final SecureRandom rng = new SecureRandom();
    private final Duration otpTtl;
    private final int otpMaxAttempts;

    public CredentialService(CredentialRepository repo, PasswordEncoder encoder, SecretCipher cipher,
                             @Value("${yaya.identity.otp.ttl-seconds:600}") long otpTtlSeconds,
                             @Value("${yaya.identity.otp.max-attempts:5}") int otpMaxAttempts) {
        this.repo = repo;
        this.encoder = encoder;
        this.cipher = cipher;
        this.otpTtl = Duration.ofSeconds(otpTtlSeconds);
        this.otpMaxAttempts = otpMaxAttempts;
    }

    @Transactional
    public void setPassword(UserId user, String rawPassword) {
        if (rawPassword == null || rawPassword.length() < 12) {
            throw new WeakPasswordException();
        }
        // Revoke the previous active password so the partial-unique index holds.
        // Flush the revoke BEFORE inserting the new row — Hibernate would otherwise
        // order the INSERT ahead of the UPDATE in one flush and trip the index.
        List<CredentialEntity> active = repo.findByUserIdAndKindAndStatus(
                user.value(), CredentialKind.PASSWORD.name(), "ACTIVE");
        for (CredentialEntity old : active) {
            old.setStatus("REVOKED");
        }
        if (!active.isEmpty()) {
            repo.flush();
        }
        repo.saveAndFlush(new CredentialEntity(
                "cred_" + UUID.randomUUID().toString().replace("-", ""),
                user.value(), CredentialKind.PASSWORD, encoder.encode(rawPassword),
                Instant.now(), null));
    }

    @Transactional(readOnly = true)
    public boolean verifyPassword(UserId user, String rawPassword) {
        return activePassword(user.value())
                .map(c -> encoder.matches(rawPassword, c.getSecretRef()))
                .orElse(false);
    }

    Optional<CredentialEntity> activePassword(String userId) {
        return repo.findFirstByUserIdAndKindAndStatusOrderByCreatedAtDesc(
                userId, CredentialKind.PASSWORD.name(), "ACTIVE");
    }

    /** Issue a 6-digit OTP; returns the raw code to deliver out-of-band (SMS). */
    @Transactional
    public String issueOtp(UserId user) {
        String code = String.format("%06d", rng.nextInt(1_000_000));
        // Supersede any outstanding OTP so only the newest is valid.
        for (CredentialEntity old : repo.findByUserIdAndKindAndStatus(
                user.value(), CredentialKind.PHONE_OTP.name(), "ACTIVE")) {
            old.setStatus("EXPIRED");
        }
        repo.saveAndFlush(new CredentialEntity(
                "cred_" + UUID.randomUUID().toString().replace("-", ""),
                user.value(), CredentialKind.PHONE_OTP, encoder.encode(code),
                Instant.now(), Instant.now().plus(otpTtl)));
        return code;
    }

    @Transactional
    public boolean verifyOtp(UserId user, String code) {
        Optional<CredentialEntity> found = repo.findFirstByUserIdAndKindAndStatusOrderByCreatedAtDesc(
                user.value(), CredentialKind.PHONE_OTP.name(), "ACTIVE");
        if (found.isEmpty()) return false;
        CredentialEntity otp = found.get();

        if (otp.getExpiresAt() != null && Instant.now().isAfter(otp.getExpiresAt())) {
            otp.setStatus("EXPIRED");
            return false;
        }
        if (otp.getAttempts() >= otpMaxAttempts) {
            otp.setStatus("EXPIRED");    // locked out
            return false;
        }
        if (encoder.matches(code, otp.getSecretRef())) {
            otp.markConsumed(Instant.now());   // single-use
            return true;
        }
        otp.incrementAttempts();
        return false;
    }

    /** Enrol TOTP MFA. The secret is stored ENCRYPTED (reversible), not hashed —
     *  codes are recomputed on each verify. Returns the Base32 secret for the QR/otpauth URI. */
    @Transactional
    public String enrollTotp(UserId user) {
        for (CredentialEntity old : repo.findByUserIdAndKindAndStatus(
                user.value(), CredentialKind.TOTP.name(), "ACTIVE")) {
            old.setStatus("REVOKED");
        }
        byte[] secret = new byte[20];
        rng.nextBytes(secret);
        String base32 = Totp.base32(secret);
        SecretCipher.Encrypted env = cipher.encrypt(base32);
        repo.saveAndFlush(new CredentialEntity(
                "cred_" + UUID.randomUUID().toString().replace("-", ""),
                user.value(), CredentialKind.TOTP, env.ciphertext() + "|" + env.iv(),
                Instant.now(), null));
        return base32;
    }

    @Transactional(readOnly = true)
    public boolean verifyTotp(UserId user, String code) {
        Optional<CredentialEntity> found = repo.findFirstByUserIdAndKindAndStatusOrderByCreatedAtDesc(
                user.value(), CredentialKind.TOTP.name(), "ACTIVE");
        if (found.isEmpty()) return false;
        String[] parts = found.get().getSecretRef().split("\\|", 2);
        String base32 = cipher.decrypt(parts[0], parts[1]);
        return Totp.verify(Totp.fromBase32(base32), code, Instant.now().getEpochSecond(), 1);
    }

    @Transactional(readOnly = true)
    public boolean hasTotp(UserId user) {
        return repo.findFirstByUserIdAndKindAndStatusOrderByCreatedAtDesc(
                user.value(), CredentialKind.TOTP.name(), "ACTIVE").isPresent();
    }

    /** Revoke every active credential — used when a user is deleted or disabled. */
    @Transactional
    public void revokeAll(UserId user) {
        for (CredentialEntity c : repo.findByUserId(user.value())) {
            if ("ACTIVE".equals(c.getStatus())) {
                c.setStatus("REVOKED");
            }
        }
    }

    public static class WeakPasswordException extends RuntimeException {
        public WeakPasswordException() {
            super("password must be at least 12 characters");
        }
    }
}
