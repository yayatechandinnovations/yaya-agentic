package com.yayatechandinnovations.yayaidentity.credentials;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;

/**
 * RFC 6238 TOTP (SHA-1, 6 digits, 30-second step) with Base32 secrets, the
 * scheme authenticator apps use. Verification checks a small ±window of steps to
 * tolerate clock skew.
 */
public final class Totp {

    private static final int DIGITS = 6;
    private static final long STEP_SECONDS = 30;
    private static final String HMAC = "HmacSHA1";
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Totp() {}

    public static String code(byte[] key, long epochSeconds) {
        long counter = epochSeconds / STEP_SECONDS;
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(key, HMAC));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            int otp = binary % (int) Math.pow(10, DIGITS);
            return String.format("%0" + DIGITS + "d", otp);
        } catch (Exception e) {
            throw new IllegalStateException("TOTP computation failed", e);
        }
    }

    public static boolean verify(byte[] key, String candidate, long epochSeconds, int window) {
        for (int i = -window; i <= window; i++) {
            if (code(key, epochSeconds + i * STEP_SECONDS).equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    public static String base32(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32.charAt((buffer >> (bits - 5)) & 0x1f));
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(BASE32.charAt((buffer << (5 - bits)) & 0x1f));
        }
        return sb.toString();
    }

    public static byte[] fromBase32(String s) {
        String clean = s.trim().toUpperCase().replace("=", "");
        int buffer = 0, bits = 0;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (char c : clean.toCharArray()) {
            int v = BASE32.indexOf(c);
            if (v < 0) throw new IllegalArgumentException("invalid base32 char: " + c);
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
