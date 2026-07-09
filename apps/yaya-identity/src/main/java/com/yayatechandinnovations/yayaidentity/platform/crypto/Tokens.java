package com.yayatechandinnovations.yayaidentity.platform.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * High-entropy opaque tokens (invitations now; password-reset later). The raw
 * token is shown once (emailed/SMSed); only its SHA-256 hash is stored. The hash
 * is deterministic — unlike a salted password hash — precisely so a presented
 * token can be looked up by hash. Safe here because the token itself has ~256
 * bits of entropy, so it isn't brute-forceable.
 */
public final class Tokens {

    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private Tokens() {}

    public static String generate() {
        byte[] raw = new byte[32];
        RNG.nextBytes(raw);
        return URL.encodeToString(raw);
    }

    public static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
