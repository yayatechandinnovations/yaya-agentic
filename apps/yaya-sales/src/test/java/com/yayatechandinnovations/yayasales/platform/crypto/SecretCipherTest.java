package com.yayatechandinnovations.yayasales.platform.crypto;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void roundTrips() {
        SecretCipher cipher = new SecretCipher(KEY);
        SecretCipher.Encrypted enc = cipher.encrypt("psp-secret-value");

        assertThat(cipher.decrypt(enc.ciphertext(), enc.iv())).isEqualTo("psp-secret-value");
        assertThat(enc.ciphertext()).isNotEqualTo("psp-secret-value");
    }

    @Test
    void freshIvPerEncryption() {
        SecretCipher cipher = new SecretCipher(KEY);
        assertThat(cipher.encrypt("x").iv()).isNotEqualTo(cipher.encrypt("x").iv());
    }

    @Test
    void tamperedCiphertextFails() {
        SecretCipher cipher = new SecretCipher(KEY);
        SecretCipher.Encrypted enc = cipher.encrypt("secret");
        byte[] bytes = Base64.getDecoder().decode(enc.ciphertext());
        bytes[0] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(bytes);

        assertThatThrownBy(() -> cipher.decrypt(tampered, enc.iv()))
                .isInstanceOf(SecretCipher.SecretCipherException.class);
    }

    @Test
    void unconfiguredBootsButRefusesToWork() {
        SecretCipher cipher = new SecretCipher("");
        assertThat(cipher.isConfigured()).isFalse();
        assertThatThrownBy(() -> cipher.encrypt("x"))
                .isInstanceOf(SecretCipher.SecretCipherException.class);
    }

    @Test
    void rejectsWrongKeyLength() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> new SecretCipher(shortKey)).isInstanceOf(IllegalStateException.class);
    }
}
