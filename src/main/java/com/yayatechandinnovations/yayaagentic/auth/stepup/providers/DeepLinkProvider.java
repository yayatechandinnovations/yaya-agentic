package com.yayatechandinnovations.yayaagentic.auth.stepup.providers;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.yayatechandinnovations.yayaagentic.auth.stepup.StepUpMethod;
import com.yayatechandinnovations.yayaagentic.auth.stepup.StepUpProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Default {@link StepUpProvider} for {@link StepUpMethod.DeepLink}.
 *
 * <p>Mints HMAC-SHA256 JWTs with {@code aud: wa-session:<sessionId>:audience:<audience>}
 * and a fresh {@code jti}, embeds them in the operator-configured landing URL,
 * and verifies them when the tenant's web app POSTs back. Same secret on both
 * sides — the platform owns it.
 *
 * <p>The secret-and-landing-URL pair must be configured before this provider
 * is usable; an unconfigured deployment raises {@code IllegalStateException}
 * on {@link #start} which the {@code StepUpCoordinator} surfaces as a
 * {@code failed:start:...} audit row.
 *
 * <p>See {@code whatsapp-channel-auth-design.md} §6.3 + §10.3.
 */
@Component
public class DeepLinkProvider implements StepUpProvider {

    private static final Logger log = LoggerFactory.getLogger(DeepLinkProvider.class);

    private final DeepLinkProperties props;

    public DeepLinkProvider(DeepLinkProperties props) {
        this.props = props;
    }

    @Override
    public StepUpMethod method() {
        return new StepUpMethod.DeepLink();
    }

    @Override
    public StartResult start(StepUpStartContext ctx) {
        if (!props.isConfigured()) {
            throw new IllegalStateException(
                    "deep-link provider not configured (set yaya.agentic.auth.step-up.deep-link.{secret,landingUrl})");
        }
        String jti = UUID.randomUUID().toString();
        String aud = audience(ctx.sessionId().value(), ctx.audience());
        Instant now = Instant.now();
        Instant expires = now.plus(props.getJwtTtl());

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(ctx.initialSubject())
                .audience(aud)
                .jwtID(jti)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expires))
                .claim("audience_id", ctx.audience())
                .claim("session_id", ctx.sessionId().value())
                .claim("tenant", ctx.tenant().value())
                .build();

        String jwt = sign(claims);
        URI url = URI.create(props.getLandingUrl()
                + (props.getLandingUrl().contains("?") ? "&" : "?")
                + "token=" + jwt);

        return new StartResult(
                "",                          // no OTP hash
                jti,
                masked(ctx.initialSubject()),
                "Tap the link to verify it's you. The link expires in "
                        + props.getJwtTtl().toMinutes() + " minute"
                        + (props.getJwtTtl().toMinutes() == 1 ? "" : "s")
                        + ".",
                Optional.of(url),
                expires);
    }

    @Override
    public VerifyResult verify(StepUpVerifyContext ctx) {
        if (!props.isConfigured()) {
            return VerifyResult.failure("provider_not_configured");
        }
        if (ctx.userSuppliedSecret() == null || ctx.userSuppliedSecret().isBlank()) {
            return VerifyResult.failure("missing_token");
        }
        try {
            SignedJWT jwt = SignedJWT.parse(ctx.userSuppliedSecret());
            if (!jwt.verify(new MACVerifier(decodeSecret()))) {
                return VerifyResult.failure("bad_signature");
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Date exp = claims.getExpirationTime();
            if (exp == null || exp.toInstant().isBefore(Instant.now())) {
                return VerifyResult.failure("expired");
            }
            String wantAud = audience(ctx.sessionId().value(), ctx.audience());
            if (claims.getAudience() == null
                    || !claims.getAudience().contains(wantAud)) {
                return VerifyResult.failure("wrong_audience");
            }
            String tokenJti = claims.getJWTID();
            if (tokenJti == null || !tokenJti.equals(ctx.deepLinkJti())) {
                return VerifyResult.failure("jti_mismatch");
            }
            String subject = claims.getSubject();
            return VerifyResult.success(
                    subject == null ? "stepup:" + ctx.sessionId().value() : subject,
                    Set.of("stepup:" + ctx.audience()),
                    Map.of(
                            "stepup.audience", ctx.audience(),
                            "stepup.method", "deep_link"),
                    "deep_link_verified");
        } catch (ParseException e) {
            return VerifyResult.failure("malformed_token");
        } catch (JOSEException e) {
            log.warn("deep-link verify failed: {}", e.getMessage());
            return VerifyResult.failure("verify_internal_error");
        }
    }

    private static String audience(String sessionId, String audience) {
        return "wa-session:" + sessionId + ":audience:" + audience;
    }

    private String sign(JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.HS256).build(),
                    claims);
            jwt.sign(new MACSigner(decodeSecret()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("deep-link sign failed", e);
        }
    }

    private byte[] decodeSecret() {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(props.getSecret());
        } catch (IllegalArgumentException e) {
            // Allow a raw-string secret too — falls through to UTF-8 bytes.
            decoded = props.getSecret().getBytes();
        }
        if (decoded.length < 32) {
            throw new IllegalStateException(
                    "deep-link secret must be at least 256 bits (32 bytes); got "
                            + decoded.length);
        }
        return decoded;
    }

    private static String masked(String subject) {
        if (subject == null || subject.isBlank()) return "***";
        return "[link sent]";
    }

    /** Validate the platform secret authenticates the callback POST.
     *  Constant-time string comparison. */
    public boolean isCallbackTokenValid(String supplied) {
        if (supplied == null || supplied.isBlank()) return false;
        String expected = props.getSecret();
        if (expected == null || expected.isBlank()) return false;
        return constantTimeEquals(supplied, expected);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
