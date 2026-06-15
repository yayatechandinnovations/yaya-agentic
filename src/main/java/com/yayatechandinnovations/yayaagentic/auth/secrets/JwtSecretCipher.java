package com.yayatechandinnovations.yayaagentic.auth.secrets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM authenticated encryption for credentials at rest
 * (HMAC keys, tenant secrets, anything we must store but never want a
 * raw DB dump to leak). The master key is supplied via
 * {@code yaya.agentic.secrets.encryption-key} (env
 * {@code YAYA_AGENTIC_SECRETS_ENCRYPTION_KEY}) as a base64-encoded
 * 32-byte (256-bit) AES key.
 *
 * <p>Wire-shape of an encrypted blob: ciphertext (base64) + 12-byte IV
 * (base64) stored in two columns. Each encryption uses a fresh IV — GCM
 * is catastrophically insecure under IV reuse, so we never reuse.</p>
 *
 * <p>If the master key isn't configured the bean still loads (so the
 * app boots without HS256 support) but every encrypt/decrypt call
 * throws a clear {@link JwtSecretCipherException}. Admin endpoints surface
 * that as a 503 with a config-fix message rather than a silent failure.</p>
 */
@Component
public class JwtSecretCipher {

    private static final String AES = "AES";
    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private final SecretKey masterKey;
    private final SecureRandom rng = new SecureRandom();

    public JwtSecretCipher(@Value("${yaya.agentic.secrets.encryption-key:}") String configuredKey) {
        this.masterKey = parseKey(configuredKey);
    }

    /** True when a master key is configured. Admin endpoints check this
     *  before accepting writes so the operator gets a clear error rather
     *  than a row that can't be decrypted at read time. */
    public boolean isConfigured() {
        return masterKey != null;
    }

    public Encrypted encrypt(String plaintext) {
        if (masterKey == null) throw notConfigured();
        if (plaintext == null) throw new JwtSecretCipherException("plaintext is required");
        try {
            byte[] iv = new byte[IV_BYTES];
            rng.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new Encrypted(
                    Base64.getEncoder().encodeToString(ciphertext),
                    Base64.getEncoder().encodeToString(iv));
        } catch (Exception e) {
            throw new JwtSecretCipherException("encryption failed: " + e.getMessage(), e);
        }
    }

    public String decrypt(String ciphertextB64, String ivB64) {
        if (masterKey == null) throw notConfigured();
        if (ciphertextB64 == null || ivB64 == null) {
            throw new JwtSecretCipherException("ciphertext and iv are required");
        }
        try {
            byte[] iv = Base64.getDecoder().decode(ivB64);
            byte[] ciphertext = Base64.getDecoder().decode(ciphertextB64);
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new JwtSecretCipherException("decryption failed (master key changed or row corrupted?): "
                    + e.getMessage(), e);
        }
    }

    private static SecretKey parseKey(String configured) {
        if (configured == null || configured.isBlank()) return null;
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(configured.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "yaya.agentic.secrets.encryption-key must be base64 — generate one with "
                            + "`openssl rand -base64 32`.", e);
        }
        if (raw.length != KEY_BYTES) {
            throw new IllegalStateException(
                    "yaya.agentic.secrets.encryption-key must decode to exactly " + KEY_BYTES
                            + " bytes (AES-256); got " + raw.length + ". Generate a new one with "
                            + "`openssl rand -base64 32`.");
        }
        return new SecretKeySpec(raw, AES);
    }

    private JwtSecretCipherException notConfigured() {
        return new JwtSecretCipherException(
                "secret cipher is not configured — set yaya.agentic.secrets.encryption-key "
                        + "(env YAYA_AGENTIC_SECRETS_ENCRYPTION_KEY) to a base64-encoded 32-byte AES key. "
                        + "Generate one with `openssl rand -base64 32`.");
    }

    /** A ciphertext / IV pair, both base64. */
    public record Encrypted(String ciphertext, String iv) {}

    /** Thrown for any encryption/decryption failure — including the
     *  "not configured" case so the admin layer can return 503 without
     *  inspecting cause chains. */
    public static class JwtSecretCipherException extends RuntimeException {
        public JwtSecretCipherException(String message) { super(message); }
        public JwtSecretCipherException(String message, Throwable cause) { super(message, cause); }
    }
}
