package com.yayatechandinnovations.yayaidentity.platform.keys;

import com.nimbusds.jose.jwk.RSAKey;
import com.yayatechandinnovations.yayaidentity.platform.Ids.KeyId;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RsaKeysTest {

    @Test
    void pemRoundTripsPublicAndPrivate() {
        KeyPair pair = RsaKeys.generate();
        RSAPublicKey pub = (RSAPublicKey) pair.getPublic();
        RSAPrivateKey priv = (RSAPrivateKey) pair.getPrivate();

        assertEquals(pub, RsaKeys.publicFromPem(RsaKeys.toPublicPem(pub)));
        assertEquals(priv.getModulus(),
                RsaKeys.privateFromPem(RsaKeys.toPrivatePem(priv)).getModulus());
    }

    @Test
    void thumbprintIsStableForTheSameKey() {
        KeyPair pair = RsaKeys.generate();
        RSAPublicKey pub = (RSAPublicKey) pair.getPublic();
        assertEquals(RsaKeys.thumbprint(pub), RsaKeys.thumbprint(pub));
    }

    @Test
    void publicJwkNeverCarriesPrivateMaterial() {
        KeyPair pair = RsaKeys.generate();
        RSAPublicKey pub = (RSAPublicKey) pair.getPublic();
        RSAPrivateKey priv = (RSAPrivateKey) pair.getPrivate();
        SigningKey key = new SigningKey(new KeyId(RsaKeys.thumbprint(pub)), RsaKeys.ALG,
                pub, priv, SigningKey.Status.ACTIVE, Instant.now());

        RSAKey jwk = RsaKeys.toPublicJwk(key);
        assertFalse(jwk.isPrivate());
        assertNull(jwk.toJSONObject().get("d"));   // private exponent must be absent
        assertEquals("RS256", jwk.getAlgorithm().getName());
        assertEquals(key.kid().value(), jwk.getKeyID());
    }

    @Test
    void activeKeyIsPublishableRetiredIsNot() {
        KeyPair pair = RsaKeys.generate();
        RSAPublicKey pub = (RSAPublicKey) pair.getPublic();
        KeyId kid = new KeyId(RsaKeys.thumbprint(pub));
        assertTrue(new SigningKey(kid, RsaKeys.ALG, pub, null,
                SigningKey.Status.RETIRING, Instant.now()).isPublishable());
        assertFalse(new SigningKey(kid, RsaKeys.ALG, pub, null,
                SigningKey.Status.RETIRED, Instant.now()).isPublishable());
    }
}
