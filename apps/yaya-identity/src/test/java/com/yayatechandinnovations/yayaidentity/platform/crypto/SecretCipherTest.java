package com.yayatechandinnovations.yayaidentity.platform.crypto;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void roundTrips() {
        SecretCipher cipher = new SecretCipher(KEY);
        SecretCipher.Encrypted enc = cipher.encrypt("s3cr3t-private-key-material");
        assertEquals("s3cr3t-private-key-material", cipher.decrypt(enc.ciphertext(), enc.iv()));
    }

    @Test
    void usesAFreshIvEachTime() {
        SecretCipher cipher = new SecretCipher(KEY);
        SecretCipher.Encrypted a = cipher.encrypt("same");
        SecretCipher.Encrypted b = cipher.encrypt("same");
        // GCM is catastrophic under IV reuse — verify we never reuse one.
        assertNotEquals(a.iv(), b.iv());
        assertNotEquals(a.ciphertext(), b.ciphertext());
    }

    @Test
    void unconfiguredCipherReportsAndRefuses() {
        SecretCipher cipher = new SecretCipher("");
        assertFalse(cipher.isConfigured());
        assertThrows(SecretCipher.SecretCipherException.class, () -> cipher.encrypt("x"));
    }

    @Test
    void rejectsWrongLengthKey() {
        assertThrows(IllegalStateException.class,
                () -> new SecretCipher(Base64.getEncoder().encodeToString(new byte[16])));
    }

    @Test
    void configuredKeyIsUsable() {
        assertTrue(new SecretCipher(KEY).isConfigured());
    }
}
