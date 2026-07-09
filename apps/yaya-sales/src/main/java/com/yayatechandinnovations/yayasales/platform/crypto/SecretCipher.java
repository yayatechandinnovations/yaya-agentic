package com.yayatechandinnovations.yayasales.platform.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM authenticated encryption for sales secrets at rest — PSP and
 * carrier credentials, courier KYC document blobs (design §15). Mirrors
 * yaya-identity's {@code SecretCipher}; distinct master key so the apps share
 * no crypto material.
 *
 * <p>Master key: {@code yaya.sales.secrets.encryption-key} (env
 * {@code YAYA_SALES_SECRETS_ENCRYPTION_KEY}), a base64-encoded 32-byte AES key.
 * Each encryption uses a fresh 12-byte IV — GCM is catastrophically insecure
 * under IV reuse, so one is never reused.
 *
 * <p>If the master key isn't configured the bean still loads (the app boots)
 * but every encrypt/decrypt throws — callers that store secrets must fail fast
 * rather than persist unprotected material.
 */
@Component
public class SecretCipher {

    private static final String AES = "AES";
    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private final SecretKey masterKey;
    private final SecureRandom rng = new SecureRandom();

    public SecretCipher(@Value("${yaya.sales.secrets.encryption-key:}") String configuredKey) {
        this.masterKey = parseKey(configuredKey);
    }

    /** True when a master key is configured; callers gate secret writes on this. */
    public boolean isConfigured() {
        return masterKey != null;
    }

    public Encrypted encrypt(String plaintext) {
        if (masterKey == null) throw notConfigured();
        if (plaintext == null) throw new SecretCipherException("plaintext is required");
        try {
            byte[] iv = new byte[IV_BYTES];
            rng.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new Encrypted(
                    Base64.getEncoder().encodeToString(ciphertext),
                    Base64.getEncoder().encodeToString(iv));
        } catch (Exception e) {
            throw new SecretCipherException("encryption failed: " + e.getMessage(), e);
        }
    }

    public String decrypt(String ciphertextB64, String ivB64) {
        if (masterKey == null) throw notConfigured();
        if (ciphertextB64 == null || ivB64 == null) {
            throw new SecretCipherException("ciphertext and iv are required");
        }
        try {
            byte[] iv = Base64.getDecoder().decode(ivB64);
            byte[] ciphertext = Base64.getDecoder().decode(ciphertextB64);
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new SecretCipherException("decryption failed (master key changed or row corrupted?): "
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
                    "yaya.sales.secrets.encryption-key must be base64 — generate one with "
                            + "`openssl rand -base64 32`.", e);
        }
        if (raw.length != KEY_BYTES) {
            throw new IllegalStateException(
                    "yaya.sales.secrets.encryption-key must decode to exactly " + KEY_BYTES
                            + " bytes (AES-256); got " + raw.length + ". Generate a new one with "
                            + "`openssl rand -base64 32`.");
        }
        return new SecretKeySpec(raw, AES);
    }

    private SecretCipherException notConfigured() {
        return new SecretCipherException(
                "secret cipher is not configured — set yaya.sales.secrets.encryption-key "
                        + "(env YAYA_SALES_SECRETS_ENCRYPTION_KEY) to a base64-encoded 32-byte AES key. "
                        + "Generate one with `openssl rand -base64 32`.");
    }

    /** A ciphertext / IV pair, both base64. */
    public record Encrypted(String ciphertext, String iv) {}

    public static class SecretCipherException extends RuntimeException {
        public SecretCipherException(String message) { super(message); }
        public SecretCipherException(String message, Throwable cause) { super(message, cause); }
    }
}
