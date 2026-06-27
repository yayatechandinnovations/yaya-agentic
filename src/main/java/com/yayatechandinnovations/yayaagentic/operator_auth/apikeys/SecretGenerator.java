package com.yayatechandinnovations.yayaagentic.operator_auth.apikeys;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * Mints and parses {@code yk_live_<32 base62>} secrets and the derived
 * lookup/storage values. See {@code docs/design/api-keys-design.md} §4.1–§4.2.
 *
 * <ul>
 *   <li>{@link #generate()} → a fresh secret. 32 base62 chars ≈ 190 bits.</li>
 *   <li>{@link #sha256(String)} → the stored {@code secret_hash}.</li>
 *   <li>{@link #prefixOf(String)} → the 11-char indexed, non-secret prefix.</li>
 * </ul>
 *
 * <p>No pepper, no argon2: a 190-bit secret makes plain sha256 uncrackable, so
 * a KDF would only burn CPU on every authenticated request (§4.2).</p>
 */
@Component
public class SecretGenerator {

    public static final String LIVE_PREFIX = "yk_live_";
    public static final int SECRET_BODY_LEN = 32;
    public static final int TOTAL_LEN = 8 + SECRET_BODY_LEN;   // "yk_live_" + 32
    public static final int LOOKUP_PREFIX_LEN = 11;            // "yk_live_" + 3

    private static final char[] BASE62 =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    private final SecureRandom random = new SecureRandom();

    /** A fresh {@code yk_live_<32 base62>} secret. */
    public String generate() {
        StringBuilder sb = new StringBuilder(TOTAL_LEN).append(LIVE_PREFIX);
        for (int i = 0; i < SECRET_BODY_LEN; i++) {
            sb.append(BASE62[random.nextInt(BASE62.length)]);
        }
        return sb.toString();
    }

    /** First {@value #LOOKUP_PREFIX_LEN} chars — indexed, non-secret. */
    public String prefixOf(String secret) {
        if (secret == null || secret.length() < LOOKUP_PREFIX_LEN) {
            throw new IllegalArgumentException("secret too short for prefix");
        }
        return secret.substring(0, LOOKUP_PREFIX_LEN);
    }

    public byte[] sha256(String secret) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Shape gate used by the filter before any DB work. Length + alphabet only. */
    public boolean looksWellFormedLive(String secret) {
        if (secret == null || secret.length() != TOTAL_LEN) return false;
        if (!secret.startsWith(LIVE_PREFIX)) return false;
        for (int i = LIVE_PREFIX.length(); i < secret.length(); i++) {
            char c = secret.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (!ok) return false;
        }
        return true;
    }
}
