package com.yayatechandinnovations.yayaagentic.auth.stepup;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.yayatechandinnovations.yayaagentic.auth.stepup.providers.DeepLinkProperties;
import com.yayatechandinnovations.yayaagentic.auth.stepup.providers.DeepLinkProvider;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeepLinkProviderTest {

    /** 256-bit secret — Nimbus requires at least 256 bits for HS256. */
    private static final String SECRET = Base64.getEncoder().encodeToString(
            "test-secret-32-bytes-long-string!".getBytes());
    private static final String LANDING = "https://acme.example/step-up";

    private DeepLinkProvider provider(String secret, String landing) {
        var props = new DeepLinkProperties();
        props.setSecret(secret);
        props.setLandingUrl(landing);
        props.setJwtTtl(Duration.ofMinutes(5));
        return new DeepLinkProvider(props);
    }

    private final Ids.TenantId tenant = new Ids.TenantId("acme");
    private final Ids.SessionId session = new Ids.SessionId("sess-1");
    private final StepUpHint hint = new StepUpHint(
            new StepUpMethod.DeepLink(),
            "tool:transfer_funds",
            Duration.ofMinutes(30),
            Map.of());

    @Test
    void method_is_deep_link() {
        assertThat(provider(SECRET, LANDING).method())
                .isInstanceOf(StepUpMethod.DeepLink.class);
    }

    @Test
    void start_throws_when_unconfigured() {
        var ctx = new StepUpProvider.StepUpStartContext(
                tenant, session, null, "u_42",
                "tool:transfer_funds", hint, "u_42", Map.of());
        assertThatThrownBy(() -> provider("", LANDING).start(ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");
        assertThatThrownBy(() -> provider(SECRET, "").start(ctx))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void start_rejects_short_secret() {
        var ctx = new StepUpProvider.StepUpStartContext(
                tenant, session, null, "u_42",
                "tool:transfer_funds", hint, "u_42", Map.of());
        assertThatThrownBy(() -> provider("c2hvcnQ=", LANDING).start(ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("256 bits");
    }

    @Test
    void start_mints_jwt_embeds_jti_and_signed_url() {
        var ctx = new StepUpProvider.StepUpStartContext(
                tenant, session, null, "u_42",
                "tool:transfer_funds", hint, "u_42", Map.of());
        StepUpProvider.StartResult result = provider(SECRET, LANDING).start(ctx);
        assertThat(result.deepLinkJti()).isNotBlank();
        assertThat(result.deepLinkUrl()).isPresent();
        assertThat(result.deepLinkUrl().get().toString())
                .startsWith(LANDING + "?token=");
        assertThat(result.userPromptHint()).contains("verify");
        assertThat(result.expiresAt()).isNotNull();
    }

    @Test
    void verify_round_trips_minted_token_to_success() {
        var p = provider(SECRET, LANDING);
        var ctx = new StepUpProvider.StepUpStartContext(
                tenant, session, null, "u_42",
                "tool:transfer_funds", hint, "u_42", Map.of());
        StepUpProvider.StartResult result = p.start(ctx);

        // Extract token from URL
        String url = result.deepLinkUrl().get().toString();
        String token = url.substring(url.indexOf("?token=") + "?token=".length());

        StepUpProvider.VerifyResult verify = p.verify(
                new StepUpProvider.StepUpVerifyContext(
                        tenant, session, "tool:transfer_funds", hint,
                        null, result.deepLinkJti(), token));
        assertThat(verify.success()).isTrue();
        assertThat(verify.elevatedSubject()).isEqualTo("u_42");
        assertThat(verify.addedScopes()).contains("stepup:tool:transfer_funds");
        assertThat(verify.addedClaims()).containsEntry("stepup.method", "deep_link");
    }

    @Test
    void verify_rejects_token_with_wrong_audience() throws Exception {
        var p = provider(SECRET, LANDING);
        // Hand-craft a JWT with the WRONG audience.
        String jti = UUID.randomUUID().toString();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("u_42")
                .audience("wa-session:sess-1:audience:wrong_audience")
                .jwtID(jti)
                .expirationTime(Date.from(java.time.Instant.now().plusSeconds(60)))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256).build(), claims);
        jwt.sign(new MACSigner(Base64.getDecoder().decode(SECRET)));

        StepUpProvider.VerifyResult res = p.verify(
                new StepUpProvider.StepUpVerifyContext(
                        tenant, session, "tool:transfer_funds", hint,
                        null, jti, jwt.serialize()));
        assertThat(res.success()).isFalse();
        assertThat(res.auditReason()).isEqualTo("wrong_audience");
    }

    @Test
    void verify_rejects_token_with_jti_mismatch() {
        var p = provider(SECRET, LANDING);
        var ctx = new StepUpProvider.StepUpStartContext(
                tenant, session, null, "u_42",
                "tool:transfer_funds", hint, "u_42", Map.of());
        StepUpProvider.StartResult result = p.start(ctx);
        String url = result.deepLinkUrl().get().toString();
        String token = url.substring(url.indexOf("?token=") + "?token=".length());

        // Pretend the pending row recorded a different JTI
        StepUpProvider.VerifyResult res = p.verify(
                new StepUpProvider.StepUpVerifyContext(
                        tenant, session, "tool:transfer_funds", hint,
                        null, "completely-different-jti", token));
        assertThat(res.success()).isFalse();
        assertThat(res.auditReason()).isEqualTo("jti_mismatch");
    }

    @Test
    void verify_rejects_expired_token() throws Exception {
        var p = provider(SECRET, LANDING);
        String jti = UUID.randomUUID().toString();
        // Pre-issue with expiry in the past
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("u_42")
                .audience("wa-session:sess-1:audience:tool:transfer_funds")
                .jwtID(jti)
                .expirationTime(Date.from(java.time.Instant.now().minusSeconds(60)))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256).build(), claims);
        jwt.sign(new MACSigner(Base64.getDecoder().decode(SECRET)));

        StepUpProvider.VerifyResult res = p.verify(
                new StepUpProvider.StepUpVerifyContext(
                        tenant, session, "tool:transfer_funds", hint,
                        null, jti, jwt.serialize()));
        assertThat(res.success()).isFalse();
        assertThat(res.auditReason()).isEqualTo("expired");
    }

    @Test
    void verify_rejects_token_signed_with_wrong_secret() throws Exception {
        var p = provider(SECRET, LANDING);
        String jti = UUID.randomUUID().toString();
        byte[] otherSecret = "different-secret-also-32-bytes!!".getBytes();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("u_42")
                .audience("wa-session:sess-1:audience:tool:transfer_funds")
                .jwtID(jti)
                .expirationTime(Date.from(java.time.Instant.now().plusSeconds(60)))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256).build(), claims);
        jwt.sign(new MACSigner(otherSecret));

        StepUpProvider.VerifyResult res = p.verify(
                new StepUpProvider.StepUpVerifyContext(
                        tenant, session, "tool:transfer_funds", hint,
                        null, jti, jwt.serialize()));
        assertThat(res.success()).isFalse();
        assertThat(res.auditReason()).isEqualTo("bad_signature");
    }

    @Test
    void verify_rejects_malformed_token() {
        var p = provider(SECRET, LANDING);
        StepUpProvider.VerifyResult res = p.verify(
                new StepUpProvider.StepUpVerifyContext(
                        tenant, session, "tool:transfer_funds", hint,
                        null, "any-jti", "not.a.valid.jwt"));
        assertThat(res.success()).isFalse();
        assertThat(res.auditReason()).isEqualTo("malformed_token");
    }

    @Test
    void is_callback_token_valid_uses_constant_time_compare() {
        var p = provider(SECRET, LANDING);
        assertThat(p.isCallbackTokenValid(SECRET)).isTrue();
        assertThat(p.isCallbackTokenValid("wrong")).isFalse();
        assertThat(p.isCallbackTokenValid(null)).isFalse();
        assertThat(p.isCallbackTokenValid("")).isFalse();
    }
}
