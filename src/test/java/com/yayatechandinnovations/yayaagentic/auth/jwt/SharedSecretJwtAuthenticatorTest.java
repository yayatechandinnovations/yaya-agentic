package com.yayatechandinnovations.yayaagentic.auth.jwt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.auth.AuthenticationException;
import com.yayatechandinnovations.yayaagentic.auth.secrets.SecretCipher;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.persistence.TenantJwtSecretEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantJwtSecretRepository;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end tests for {@link SharedSecretJwtAuthenticator}: signs real
 * HS256 JWTs with Nimbus and runs them through the authenticator, plus
 * exercises the early-return and untrusted-issuer paths. The
 * {@link SecretCipher} is constructed with a real base64 key so the
 * encrypt → store → decrypt → verify round-trip is also covered.
 */
class SharedSecretJwtAuthenticatorTest {

    private static final Ids.TenantId TENANT = new Ids.TenantId("lottery-dr");
    private static final String ISS = "smart-lottery-identity";
    private static final String HMAC_SECRET = "the-secret-the-identity-service-and-yaya-share-32+";

    private final TenantJwtSecretRepository repo = mock(TenantJwtSecretRepository.class);
    private final ObjectMapper json = new ObjectMapper();
    private final SecretCipher cipher = new SecretCipher(
            // 32 raw bytes -> 44 base64 chars. Hand-rolled so tests don't depend
            // on env vars; production reads YAYA_AGENTIC_SECRETS_ENCRYPTION_KEY.
            Base64.getEncoder().encodeToString(new byte[32]));

    private final SharedSecretJwtAuthenticator auth =
            new SharedSecretJwtAuthenticator(repo, cipher, json);

    @Test
    void valid_hs256_jwt_produces_principal_with_claims_and_scopes() throws Exception {
        givenStoredSecret(ISS, HMAC_SECRET, List.of());

        String token = signedHs256(ISS, "user-42",
                Map.of("scope", "PLAYER_QUERY_PUBLIC PLAYER_BETTING", "role", "PLAYER"));

        Optional<Principal> hit = auth.tryAuthenticate(bearerCtx(token));

        assertThat(hit).isPresent();
        assertThat(hit.get().subject()).isEqualTo("user-42");
        assertThat(hit.get().scopes()).containsExactlyInAnyOrder("PLAYER_QUERY_PUBLIC", "PLAYER_BETTING");
        assertThat(hit.get().claims()).containsEntry("role", "PLAYER");
    }

    @Test
    void rs256_token_is_skipped_so_the_chain_can_continue() throws Exception {
        givenStoredSecret(ISS, HMAC_SECRET, List.of());

        // Header alg "RS256" — this authenticator should yield to OIDC.
        String token = signedJwtStub("RS256", ISS, "user-1");

        Optional<Principal> hit = auth.tryAuthenticate(bearerCtx(token));

        assertThat(hit).isEmpty();
    }

    @Test
    void non_bearer_header_yields_empty() throws Exception {
        Optional<Principal> hit = auth.tryAuthenticate(new AuthContext(TENANT,
                Map.of("Authorization", "Basic dXNlcjpwYXNz"), Optional.empty()));
        assertThat(hit).isEmpty();
    }

    @Test
    void bearer_garbage_yields_empty() throws Exception {
        Optional<Principal> hit = auth.tryAuthenticate(bearerCtx("not-a-jwt"));
        assertThat(hit).isEmpty();
    }

    @Test
    void unknown_iss_fails_loud_with_trusted_set() throws Exception {
        givenStoredSecret(ISS, HMAC_SECRET, List.of());

        String token = signedHs256("some-other-issuer", "user-1", Map.of());

        assertThatThrownBy(() -> auth.tryAuthenticate(bearerCtx(token)))
                .isInstanceOf(AuthenticationException.class)
                .hasMessageContaining("untrusted issuer 'some-other-issuer'")
                .hasMessageContaining(ISS);
    }

    @Test
    void wrong_secret_fails_signature_mismatch() throws Exception {
        givenStoredSecret(ISS, HMAC_SECRET, List.of());

        // Sign with a different secret of the same length.
        String token = signedHs256WithSecret(ISS, "user-1", Map.of(),
                "WRONG-secret-with-enough-bytes-to-meet-nimbus-min");

        assertThatThrownBy(() -> auth.tryAuthenticate(bearerCtx(token)))
                .isInstanceOf(AuthenticationException.class)
                .hasMessageContaining("signature mismatch");
    }

    @Test
    void expired_token_is_rejected() throws Exception {
        givenStoredSecret(ISS, HMAC_SECRET, List.of());

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISS)
                .subject("user-1")
                .expirationTime(new Date(System.currentTimeMillis() - 60_000))
                .build();
        String token = signWith(HMAC_SECRET, claims);

        assertThatThrownBy(() -> auth.tryAuthenticate(bearerCtx(token)))
                .isInstanceOf(AuthenticationException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void audience_mismatch_is_rejected_when_audiences_configured() throws Exception {
        givenStoredSecret(ISS, HMAC_SECRET, List.of("smart-lottery-api"));

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISS)
                .subject("user-1")
                .audience("some-other-api")
                .build();
        String token = signWith(HMAC_SECRET, claims);

        assertThatThrownBy(() -> auth.tryAuthenticate(bearerCtx(token)))
                .isInstanceOf(AuthenticationException.class)
                .hasMessageContaining("audience not allowed");
    }

    @Test
    void cache_is_evicted_on_change_event() throws Exception {
        // Round 1: no secrets registered.
        when(repo.findByTenantId(eq(TENANT.value()))).thenReturn(List.of());
        String token = signedHs256(ISS, "user-1", Map.of());

        assertThatThrownBy(() -> auth.tryAuthenticate(bearerCtx(token)))
                .isInstanceOf(AuthenticationException.class)
                .hasMessageContaining("untrusted issuer");

        // Operator registers a secret. Without eviction the empty cache would
        // stick and the next call would still fail.
        givenStoredSecret(ISS, HMAC_SECRET, List.of());
        auth.onTenantJwtSecretsChanged(new TenantJwtSecretsChangedEvent(TENANT.value()));

        Optional<Principal> hit = auth.tryAuthenticate(bearerCtx(token));
        assertThat(hit).isPresent();
        assertThat(hit.get().subject()).isEqualTo("user-1");
    }

    // ---- Helpers ------------------------------------------------------------

    private void givenStoredSecret(String iss, String plaintextSecret, List<String> audiences)
            throws Exception {
        SecretCipher.Encrypted enc = cipher.encrypt(plaintextSecret);
        String audsJson = json.writeValueAsString(audiences);
        TenantJwtSecretEntity entity = new TenantJwtSecretEntity(
                TENANT.value(), iss, enc.ciphertext(), enc.iv(), audsJson);
        when(repo.findByTenantId(eq(TENANT.value()))).thenReturn(List.of(entity));
    }

    private AuthContext bearerCtx(String token) {
        return new AuthContext(TENANT,
                Map.of("Authorization", "Bearer " + token), Optional.empty());
    }

    private static String signedHs256(String iss, String sub, Map<String, Object> extraClaims)
            throws JOSEException {
        return signedHs256WithSecret(iss, sub, extraClaims, HMAC_SECRET);
    }

    private static String signedHs256WithSecret(String iss, String sub,
                                                Map<String, Object> extraClaims,
                                                String secret) throws JOSEException {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .issuer(iss)
                .subject(sub)
                .expirationTime(new Date(System.currentTimeMillis() + 300_000));
        extraClaims.forEach(builder::claim);
        return signWith(secret, builder.build());
    }

    private static String signWith(String secret, JWTClaimsSet claims) throws JOSEException {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    private static String signedJwtStub(String alg, String iss, String sub) {
        String header = base64Url("{\"alg\":\"" + alg + "\",\"typ\":\"JWT\"}");
        String payload = base64Url("{\"iss\":\"" + iss + "\",\"sub\":\"" + sub
                + "\",\"exp\":9999999999}");
        String sig = base64Url("fake-signature");
        return header + "." + payload + "." + sig;
    }

    private static String base64Url(String s) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
