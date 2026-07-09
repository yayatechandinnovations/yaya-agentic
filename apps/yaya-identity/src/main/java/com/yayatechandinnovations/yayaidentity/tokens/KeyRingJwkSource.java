package com.yayatechandinnovations.yayaidentity.tokens;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.yayatechandinnovations.yayaidentity.platform.keys.KeyRing;
import com.yayatechandinnovations.yayaidentity.platform.keys.SigningKey;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapts the platform {@link KeyRing} to the Nimbus {@link JWKSource} Spring
 * Authorization Server uses for both signing and JWKS publication.
 *
 * <p>The set is rebuilt on every selection so key rotation is reflected without
 * a restart. The {@code ACTIVE} key is returned WITH its private key (SAS needs
 * it to sign); {@code RETIRING} keys are public-only (verification during the
 * make-before-break window). SAS's JWK-Set endpoint strips private material, so
 * {@code /oauth2/jwks} only ever exposes public keys.
 */
public class KeyRingJwkSource implements JWKSource<SecurityContext> {

    private final KeyRing keyRing;

    public KeyRingJwkSource(KeyRing keyRing) {
        this.keyRing = keyRing;
    }

    @Override
    public List<JWK> get(com.nimbusds.jose.jwk.JWKSelector selector, SecurityContext context) {
        return selector.select(build());
    }

    private JWKSet build() {
        SigningKey active = keyRing.active();
        List<JWK> keys = new ArrayList<>();
        keys.add(new RSAKey.Builder(active.publicKey())
                .privateKey(active.privateKey())
                .keyID(active.kid().value())
                .keyUse(com.nimbusds.jose.jwk.KeyUse.SIGNATURE)
                .algorithm(com.nimbusds.jose.JWSAlgorithm.RS256)
                .build());
        // RETIRING keys (public only) so tokens signed before rotation still verify.
        for (JWK pub : keyRing.publicJwks().getKeys()) {
            if (!pub.getKeyID().equals(active.kid().value())) {
                keys.add(pub);
            }
        }
        return new JWKSet(keys);
    }
}
