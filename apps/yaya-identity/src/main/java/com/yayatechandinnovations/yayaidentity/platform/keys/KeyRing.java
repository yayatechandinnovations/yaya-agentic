package com.yayatechandinnovations.yayaidentity.platform.keys;

import com.nimbusds.jose.jwk.JWKSet;

/**
 * The signing-key SPI (design §15). Owns the {@code ACTIVE} key new tokens are
 * signed with, publishes the public {@link JWKSet} relying parties verify
 * against, and rotates make-before-break so verification never has a gap
 * (NFR-Avail-3).
 *
 * <p>Private key material is encrypted at rest via the platform
 * {@code SecretCipher}; only public keys ever leave this module.
 */
public interface KeyRing {

    /** The key to sign new tokens with. Never null once the ring is bootstrapped. */
    SigningKey active();

    /** Public JWKS: every {@code ACTIVE} + {@code RETIRING} key, public parts only. */
    JWKSet publicJwks();

    /**
     * Mint a new {@code ACTIVE} key and demote the previous active to
     * {@code RETIRING} (kept in JWKS so in-flight tokens still verify).
     * Returns the new active key.
     */
    SigningKey rotate();
}
