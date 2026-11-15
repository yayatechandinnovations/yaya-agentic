package com.yayatechandinnovations.yayaagentic.headless;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.yayatechandinnovations.yayaagentic.auth.jwt.SharedSecretJwtAuthenticator;
import com.yayatechandinnovations.yayaagentic.auth.secrets.JwtSecretCipher;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.persistence.TenantJwtSecretEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantJwtSecretRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Admission to {@code /v1/headless/runs}: the tenant comes from the token, and
 * only a tenant service JWT minted for this service with scope
 * {@code headless:run} gets in — over a real {@link SharedSecretJwtAuthenticator}
 * and an in-memory {@code tenant_jwt_secrets}.
 */
class HeadlessRunAuthenticatorTest {

    private static final String TENANT = "bancadh";
    private static final String ISS = "smart-lottery-identity:bancadh";
    private static final String SECRET = "the-outbound-key-smart-lottery-and-yaya-share-32+";

    private final TenantJwtSecretRepository repo = mock(TenantJwtSecretRepository.class);
    private final JwtSecretCipher cipher = new JwtSecretCipher(Base64.getEncoder().encodeToString(new byte[32]));
    private final List<TenantJwtSecretEntity> rows = new ArrayList<>();
    private final HeadlessRunAuthenticator auth = new HeadlessRunAuthenticator(repo,
            new SharedSecretJwtAuthenticator(repo, cipher, new ObjectMapper()));

    @BeforeEach
    void trustTheTenant() {
        when(repo.findByIss(anyString())).thenAnswer(
                i -> rows.stream().filter(r -> r.getIss().equals(i.getArgument(0))).toList());
        when(repo.findByTenantId(anyString())).thenAnswer(
                i -> rows.stream().filter(r -> r.getTenantId().equals(i.getArgument(0))).toList());
        trust(TENANT, ISS, SECRET);
    }

    @Test
    void a_service_token_with_the_scope_resolves_its_tenant_from_the_issuer() {
        Principal principal = auth.authenticate("Bearer " + sign(SECRET, claims(ISS, "headless:run").build()));

        assertThat(principal.tenant().value()).isEqualTo(TENANT);
        assertThat(principal.subject()).isEqualTo("service:automations");
        // Among several scopes too.
        assertThat(auth.authenticate("Bearer " + sign(SECRET,
                claims(ISS, "outreach:send headless:run").build())).tenant().value()).isEqualTo(TENANT);
    }

    @Test
    void an_issuer_shared_by_two_tenants_resolves_to_the_one_whose_key_signed_the_token() {
        trust("other-tenant", ISS, "a-completely-different-shared-secret-of-32+-chars");

        assertThat(auth.authenticate("Bearer " + sign(SECRET, claims(ISS, "headless:run").build()))
                .tenant().value()).isEqualTo(TENANT);
    }

    @Test
    void no_credentials_or_an_anonymous_caller_is_unauthorized() {
        // There is no anonymous fallback here: the authenticator chain is never consulted.
        assertStatus(null, HttpStatus.UNAUTHORIZED, "unauthorized");
        assertStatus("", HttpStatus.UNAUTHORIZED, "unauthorized");
        assertStatus("Basic dXNlcjpwYXNz", HttpStatus.UNAUTHORIZED, "unauthorized");
        assertStatus("Bearer ", HttpStatus.UNAUTHORIZED, "unauthorized");
    }

    @Test
    void an_api_key_a_platform_service_token_or_an_opaque_bearer_is_unauthorized() {
        assertStatus("Bearer yk_live_abc12345xyz9q7w3e8r2t5y6u1i4o0p7a3", HttpStatus.UNAUTHORIZED, "unauthorized");
        // The X-Yaya-Service-Token shape (tenant.subject.exp.scopes.hmac) sent as a bearer.
        assertStatus("Bearer " + TENANT + ".service.1999999999.headless:run.c2lnbmF0dXJl",
                HttpStatus.UNAUTHORIZED, "unauthorized");
        assertStatus("Bearer not-a-jwt", HttpStatus.UNAUTHORIZED, "unauthorized");
    }

    @Test
    void a_token_signed_with_the_wrong_key_or_by_an_unknown_issuer_is_unauthorized() {
        assertStatus("Bearer " + sign("an-attacker-chosen-secret-that-is-long-enough-32+",
                claims(ISS, "headless:run").build()), HttpStatus.UNAUTHORIZED, "unauthorized");
        assertStatus("Bearer " + sign(SECRET, claims("smart-lottery-identity:nobody", "headless:run").build()),
                HttpStatus.UNAUTHORIZED, "unauthorized");
    }

    @Test
    void an_expired_token_one_without_expiry_or_for_another_audience_is_unauthorized() {
        assertStatus("Bearer " + sign(SECRET, claims(ISS, "headless:run")
                .expirationTime(new Date(System.currentTimeMillis() - 1_000)).build()),
                HttpStatus.UNAUTHORIZED, "unauthorized");
        assertStatus("Bearer " + sign(SECRET, claims(ISS, "headless:run").expirationTime(null).build()),
                HttpStatus.UNAUTHORIZED, "unauthorized");
        assertStatus("Bearer " + sign(SECRET, claims(ISS, "headless:run").audience("someone-else").build()),
                HttpStatus.UNAUTHORIZED, "unauthorized");
        assertStatus("Bearer " + sign(SECRET, claims(ISS, "headless:run").audience((String) null).build()),
                HttpStatus.UNAUTHORIZED, "unauthorized");
    }

    @Test
    void a_valid_tenant_token_without_the_scope_is_forbidden() {
        // The scope of another service endpoint, a user's scopes, none at all.
        assertStatus("Bearer " + sign(SECRET, claims(ISS, "structured:complete").build()),
                HttpStatus.FORBIDDEN, "forbidden");
        assertStatus("Bearer " + sign(SECRET, claims(ISS, "PLAYER_QUERY_PUBLIC PLAYER_BETTING").build()),
                HttpStatus.FORBIDDEN, "forbidden");
        assertStatus("Bearer " + sign(SECRET, claims(ISS, null).build()), HttpStatus.FORBIDDEN, "forbidden");
        assertStatus("Bearer " + sign(SECRET, claims(ISS, "headless:runs").build()), HttpStatus.FORBIDDEN, "forbidden");
    }

    private void assertStatus(String header, HttpStatus status, String code) {
        assertThatThrownBy(() -> auth.authenticate(header))
                .isInstanceOfSatisfying(HeadlessRunException.class, e -> {
                    assertThat(e.status()).isEqualTo(status);
                    assertThat(e.code()).isEqualTo(code);
                });
    }

    private void trust(String tenant, String iss, String secret) {
        JwtSecretCipher.Encrypted enc = cipher.encrypt(secret);
        rows.add(new TenantJwtSecretEntity(tenant, iss, enc.ciphertext(), enc.iv(), "[]"));
    }

    private static JWTClaimsSet.Builder claims(String iss, String scope) {
        Date now = new Date();
        JWTClaimsSet.Builder b = new JWTClaimsSet.Builder().issuer(iss).audience("yaya-agentic")
                .subject("service:automations").issueTime(now)
                .expirationTime(new Date(now.getTime() + 60_000)).jwtID(UUID.randomUUID().toString());
        if (scope != null) b.claim("scope", scope);
        return b;
    }

    private static String sign(String secret, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("kid-1").build(), claims);
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
