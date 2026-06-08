package com.yayatechandinnovations.yayaagentic.operator_auth.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Symmetric envelope for storing the HTTP-delegate shared secret on disk.
 * Backed by Spring's {@link Encryptors#text(CharSequence, CharSequence)}
 * (PBKDF2-derived AES key + random IV per encryption).
 *
 * <p>Reads {@code YAYA_CONFIG_KEY} (password) and {@code YAYA_CONFIG_SALT}
 * (hex-encoded bytes, ≥ 16 chars). When either is unset, derives weak dev
 * defaults from a constant string and emits a loud {@code [SECURITY]}
 * warning at boot — never ship to prod without setting both.</p>
 */
@Component
public class SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);
    private static final String DEV_DEFAULT_KEY = "yaya-dev-secret-do-not-ship";
    private static final String DEV_DEFAULT_SALT_HEX = "deadbeefdeadbeefdeadbeefdeadbeef";

    private final TextEncryptor encryptor;

    public SecretCipher(@Value("${yaya.agentic.operator-auth.secret-cipher.key:}") String key,
                        @Value("${yaya.agentic.operator-auth.secret-cipher.salt:}") String saltHex) {
        boolean usingDevDefaults = isBlank(key) || isBlank(saltHex);
        String effectiveKey = isBlank(key) ? DEV_DEFAULT_KEY : key;
        String effectiveSalt = isBlank(saltHex) ? DEV_DEFAULT_SALT_HEX : saltHex;

        // Validate the salt early: Encryptors.text throws an opaque error
        // when it isn't hex — surface a useful message instead.
        try {
            HexFormat.of().parseHex(effectiveSalt);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "yaya.agentic.operator-auth.secret-cipher.salt must be hex-encoded bytes", e);
        }
        this.encryptor = Encryptors.text(effectiveKey, effectiveSalt);

        if (usingDevDefaults) {
            log.warn("[SECURITY] SecretCipher is using dev default key/salt — set "
                    + "YAYA_CONFIG_KEY and YAYA_CONFIG_SALT before exposing this instance.");
        } else {
            log.info("SecretCipher configured with operator-provided key + salt.");
        }
    }

    /** Returns {@code null} when {@code plaintext} is null or blank. */
    public byte[] encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) return null;
        String wrapped = encryptor.encrypt(plaintext);
        return wrapped.getBytes(StandardCharsets.US_ASCII);
    }

    public String decrypt(byte[] ciphertext) {
        if (ciphertext == null || ciphertext.length == 0) return null;
        String wrapped = new String(ciphertext, StandardCharsets.US_ASCII);
        return encryptor.decrypt(wrapped);
    }

    /** Convenience for surfacing presence-without-disclosure in admin GETs. */
    public static String mask(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) return "";
        return "•".repeat(Math.min(plaintext.length(), 12));
    }

    /** Encodes for transport when we need to emit it in JSON (e.g. tests). */
    public String encryptToBase64(String plaintext) {
        byte[] raw = encrypt(plaintext);
        return raw == null ? null : Base64.getEncoder().encodeToString(raw);
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
