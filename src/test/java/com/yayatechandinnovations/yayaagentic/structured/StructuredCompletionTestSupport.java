package com.yayatechandinnovations.yayaagentic.structured;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.yayatechandinnovations.yayaagentic.auth.jwt.SharedSecretJwtAuthenticator;
import com.yayatechandinnovations.yayaagentic.auth.secrets.JwtSecretCipher;
import com.yayatechandinnovations.yayaagentic.persistence.TenantJwtSecretEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantJwtSecretRepository;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A real {@link SharedSecretJwtAuthenticator} over an in-memory
 * {@code tenant_jwt_secrets} table, plus a signer for the service JWT Smart
 * Lottery mints — shared by the structured-completion tests.
 */
public final class StructuredCompletionTestSupport {

    public static final String TENANT = "bancadh";
    public static final String ISS = "smart-lottery-identity:bancadh";
    public static final String SECRET = "the-outbound-key-smart-lottery-and-yaya-share-32+";

    public final TenantJwtSecretRepository repo = mock(TenantJwtSecretRepository.class);
    private final JwtSecretCipher cipher = new JwtSecretCipher(Base64.getEncoder().encodeToString(new byte[32]));
    private final List<TenantJwtSecretEntity> rows = new ArrayList<>();

    public final StructuredCompletionAuthenticator authenticator = new StructuredCompletionAuthenticator(
            repo, new SharedSecretJwtAuthenticator(repo, cipher, new ObjectMapper()));

    /** Registers "tenant trusts iss, signed with secret" — one {@code tenant_jwt_secrets} row. */
    public StructuredCompletionTestSupport trust(String tenant, String iss, String secret) {
        JwtSecretCipher.Encrypted enc = cipher.encrypt(secret);
        rows.add(new TenantJwtSecretEntity(tenant, iss, enc.ciphertext(), enc.iv(), "[]"));
        when(repo.findByIss(iss)).thenAnswer(
                i -> rows.stream().filter(r -> r.getIss().equals(iss)).toList());
        when(repo.findByTenantId(tenant)).thenAnswer(
                i -> rows.stream().filter(r -> r.getTenantId().equals(tenant)).toList());
        return this;
    }

    /** The token Smart Lottery's Profile Builder sends. */
    public static String serviceToken() {
        return sign(SECRET, claims(ISS, "structured:complete").build());
    }

    public static JWTClaimsSet.Builder claims(String iss, String scope) {
        Date now = new Date();
        JWTClaimsSet.Builder b = new JWTClaimsSet.Builder()
                .issuer(iss)
                .audience("yaya-agentic")
                .subject("service:profile-builder")
                .issueTime(now)
                .expirationTime(new Date(now.getTime() + 60_000))
                .jwtID(UUID.randomUUID().toString());
        if (scope != null) b.claim("scope", scope);
        return b;
    }

    public static String sign(String secret, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("kid-1").build(), claims);
            jwt.sign(new MACSigner(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
