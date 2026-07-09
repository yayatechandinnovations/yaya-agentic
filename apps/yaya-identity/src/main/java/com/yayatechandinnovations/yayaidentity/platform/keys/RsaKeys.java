package com.yayatechandinnovations.yayaidentity.platform.keys;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * RSA key generation, PEM (de)serialization, and JWK conversion — the crypto
 * primitives behind the {@link KeyRing}. Kept dependency-light: JDK for the
 * keys, Nimbus only for JWK/JWKS shaping so the {@code /oauth2/jwks} output is
 * standards-correct and Spring Authorization Server (I1) can reuse it verbatim.
 */
public final class RsaKeys {

    private static final int KEY_SIZE = 2048;
    private static final String RSA = "RSA";
    public static final String ALG = "RS256";

    private RsaKeys() {}

    public static KeyPair generate() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance(RSA);
            gen.initialize(KEY_SIZE);
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("RSA key generation failed: " + e.getMessage(), e);
        }
    }

    /** RFC 7638 JWK thumbprint of the public key — used as the stable {@code kid}. */
    public static String thumbprint(RSAPublicKey publicKey) {
        try {
            return new RSAKey.Builder(publicKey).build().computeThumbprint().toString();
        } catch (JOSEException e) {
            throw new IllegalStateException("thumbprint computation failed: " + e.getMessage(), e);
        }
    }

    public static String toPublicPem(RSAPublicKey key) {
        return pem("PUBLIC KEY", key.getEncoded());          // X.509 SubjectPublicKeyInfo
    }

    public static String toPrivatePem(RSAPrivateKey key) {
        return pem("PRIVATE KEY", key.getEncoded());         // PKCS#8
    }

    public static RSAPublicKey publicFromPem(String pem) {
        try {
            byte[] der = derBytes(pem);
            return (RSAPublicKey) KeyFactory.getInstance(RSA)
                    .generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("invalid RSA public PEM: " + e.getMessage(), e);
        }
    }

    public static RSAPrivateKey privateFromPem(String pem) {
        try {
            byte[] der = derBytes(pem);
            return (RSAPrivateKey) KeyFactory.getInstance(RSA)
                    .generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("invalid RSA private PEM: " + e.getMessage(), e);
        }
    }

    /** Public-only JWK (never carries the private key) for JWKS publication. */
    public static RSAKey toPublicJwk(SigningKey key) {
        return new RSAKey.Builder(key.publicKey())
                .keyID(key.kid().value())
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .build()
                .toPublicJWK();
    }

    private static String pem(String type, byte[] der) {
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + b64 + "\n-----END " + type + "-----\n";
    }

    private static byte[] derBytes(String pem) {
        String body = pem.replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }
}
