package com.yayatechandinnovations.yayaidentity.platform.keys;

import com.yayatechandinnovations.yayaidentity.platform.Ids.KeyId;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;

/**
 * An RS256 signing key in the {@link KeyRing}. The {@code kid} is the RFC 7638
 * JWK thumbprint of the public key, so it is stable and self-describing.
 *
 * <p>Lifecycle: {@code ACTIVE} (the one key we sign new tokens with) →
 * {@code RETIRING} (still published in JWKS so tokens signed before rotation
 * keep verifying — make-before-break, NFR-Avail-3) → {@code RETIRED} (dropped
 * from JWKS once no live token could carry it).
 */
public record SigningKey(KeyId kid,
                         String alg,
                         RSAPublicKey publicKey,
                         RSAPrivateKey privateKey,   // null when loaded for verification-only use
                         Status status,
                         Instant createdAt) {

    public enum Status { ACTIVE, RETIRING, RETIRED }

    public boolean isPublishable() {
        return status == Status.ACTIVE || status == Status.RETIRING;
    }
}
