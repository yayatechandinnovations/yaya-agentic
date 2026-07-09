package com.yayatechandinnovations.yayasales.fulfillment.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Opaque bearer material for the fleet (§8.9, §15): invite tokens, courier
 * credentials, phone OTPs. Only SHA-256 digests are ever stored — a database
 * leak yields nothing presentable.
 */
final class Tokens {

    private static final SecureRandom RNG = new SecureRandom();

    private Tokens() {}

    static String newToken() {
        byte[] raw = new byte[32];
        RNG.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    static String newOtp() {
        return String.format("%06d", RNG.nextInt(1_000_000));
    }

    static String sha256(String material) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
