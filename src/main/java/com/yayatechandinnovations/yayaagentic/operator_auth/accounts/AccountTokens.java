package com.yayatechandinnovations.yayaagentic.operator_auth.accounts;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Tiny utility around random token minting and sha256-hashing. Centralised
 * so verify-email / password-reset / invitation share the same primitive.
 *
 * <p>Raw tokens use 32 bytes of {@link SecureRandom} entropy, base64-URL
 * encoded without padding. Only the sha256 lands in the DB.</p>
 */
@Component
public class AccountTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENC = Base64.getUrlEncoder().withoutPadding();

    public String mintRawToken() {
        byte[] buf = new byte[32];
        RANDOM.nextBytes(buf);
        return URL_ENC.encodeToString(buf);
    }

    public byte[] sha256(String input) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
