package com.yayatechandinnovations.yayaagentic.channel.auth;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.auth.AuthenticationException;
import com.yayatechandinnovations.yayaagentic.auth.chain.AuthenticatorChain;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.persistence.ChannelAuthResolverEntity;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Operator-facing debug surface for {@code POST /v1/admin/tenants/{t}/auth-resolvers/{name}/test}.
 * Fires the configured resolver synchronously with a sentinel caller and
 * surfaces the dispatch result + JWT verification outcome so the operator
 * can validate end-to-end wiring before pointing a real binding at it.
 *
 * <p>See {@code docs/design/channel-auth-resolver-design.md} §11 (admin
 * surface).
 */
@Component
public class AuthResolverTestProbe {

    private static final String DEFAULT_CALLER_PHONE = "+15555550100";
    private static final String DEFAULT_SENDER_ID = "snd_test_probe";
    private static final String DEFAULT_SENDER_PHONE = "+15555550199";

    private final AuthResolverRegistry registry;
    private final AuthenticatorChain authChain;
    private final Clock clock;

    public AuthResolverTestProbe(AuthResolverRegistry registry,
                                 AuthenticatorChain authChain,
                                 Clock clock) {
        this.registry = registry;
        this.authChain = authChain;
        this.clock = clock;
    }

    public ProbeResult probe(ChannelAuthResolverEntity row, ProbeInput input) {
        String callerPhone = (input == null || input.callerPhone() == null
                || input.callerPhone().isBlank())
                ? DEFAULT_CALLER_PHONE : input.callerPhone().trim();
        String senderId = (input == null || input.senderId() == null
                || input.senderId().isBlank())
                ? DEFAULT_SENDER_ID : input.senderId().trim();
        String senderPhone = (input == null || input.senderPhone() == null
                || input.senderPhone().isBlank())
                ? DEFAULT_SENDER_PHONE : input.senderPhone().trim();

        AuthResolver resolver;
        try {
            resolver = registry.materialize(row);
        } catch (AuthResolverException are) {
            return ProbeResult.error(row, are.status(), are.getMessage());
        }

        Ids.TenantId tenant = new Ids.TenantId(row.getTenantId());
        AuthResolver.ResolveRequest req = new AuthResolver.ResolveRequest(
                tenant,
                new SenderId(senderId),
                senderPhone,
                callerPhone,
                new Ids.SessionId("probe_" + UUID.randomUUID()),
                "whatsapp",
                AuthResolver.ResolveReason.INITIAL,
                Instant.now(clock));

        Instant started = Instant.now(clock);
        Optional<String> token;
        try {
            token = resolver.resolve(req);
        } catch (AuthResolverException are) {
            int elapsed = (int) (Instant.now(clock).toEpochMilli() - started.toEpochMilli());
            return ProbeResult.error(row, are.status(), are.getMessage(), elapsed,
                    are.httpStatus());
        } catch (RuntimeException rte) {
            int elapsed = (int) (Instant.now(clock).toEpochMilli() - started.toEpochMilli());
            return ProbeResult.error(row, "transport_error",
                    rte.getClass().getSimpleName() + ": " + rte.getMessage(),
                    elapsed, null);
        }
        int elapsedMs = (int) (Instant.now(clock).toEpochMilli() - started.toEpochMilli());

        if (token.isEmpty()) {
            return ProbeResult.unresolved(row, elapsedMs);
        }
        Verification verification = verify(tenant, token.get());
        return ProbeResult.success(row, elapsedMs, verification);
    }

    private Verification verify(Ids.TenantId tenant, String jwt) {
        try {
            SignedJWT parsed = SignedJWT.parse(jwt);
            JWTClaimsSet claims = parsed.getJWTClaimsSet();
            String issuer = claims.getIssuer();
            String subject = claims.getSubject();
            List<String> audience = claims.getAudience();
            Instant exp = claims.getExpirationTime() == null
                    ? null : claims.getExpirationTime().toInstant();
            String profileId = stringClaim(claims, "_profile_id");
            List<String> scopes = scopesFrom(claims);
            String kid = parsed.getHeader().getKeyID();

            // Defense: don't return an "ok" verification for a JWT we can't
            // actually verify. Run it through the existing chain.
            AuthContext ctx = new AuthContext(
                    tenant,
                    Map.of("Authorization", "Bearer " + jwt),
                    Optional.empty());
            Optional<Principal> principal;
            try {
                principal = authChain.tryAuthenticate(ctx);
            } catch (AuthenticationException ae) {
                return Verification.failed(issuer, subject, audience, exp,
                        kid, profileId, scopes, ae.getMessage());
            }
            if (principal.isEmpty()) {
                return Verification.failed(issuer, subject, audience, exp,
                        kid, profileId, scopes,
                        "no authenticator in the chain accepted the JWT — check that "
                                + "tenant_jwt_secrets or tenant_oidc_issuers has a row "
                                + "matching iss=" + issuer);
            }
            return Verification.ok(issuer, subject, audience, exp, kid, profileId,
                    principal.get().scopes().stream().sorted().toList());
        } catch (Exception parseError) {
            return Verification.malformed("could not parse JWT: "
                    + parseError.getClass().getSimpleName());
        }
    }

    private static String stringClaim(JWTClaimsSet claims, String key) {
        try {
            Object v = claims.getClaim(key);
            return v == null ? null : v.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> scopesFrom(JWTClaimsSet claims) {
        Object raw = claims.getClaim("scope");
        if (raw instanceof String s && !s.isBlank()) {
            return List.of(s.trim().split("\\s+"));
        }
        Object scp = claims.getClaim("scp");
        if (scp instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object x : list) if (x != null) out.add(x.toString());
            return out;
        }
        return List.of();
    }

    /** Operator-supplied parameters for the probe. */
    public record ProbeInput(String callerPhone, String senderId, String senderPhone) {}

    /** Verification block — what the JWT verifier saw. */
    public record Verification(
            boolean ok,
            String issuer,
            String subject,
            List<String> audience,
            Instant exp,
            String kid,
            String profileId,
            List<String> scopes,
            String reason) {

        static Verification ok(String issuer, String subject, List<String> audience,
                               Instant exp, String kid, String profileId,
                               List<String> scopes) {
            return new Verification(true, issuer, subject, audience, exp, kid,
                    profileId, scopes, null);
        }

        static Verification failed(String issuer, String subject, List<String> audience,
                                   Instant exp, String kid, String profileId,
                                   List<String> scopes, String reason) {
            return new Verification(false, issuer, subject, audience, exp, kid,
                    profileId, scopes, reason);
        }

        static Verification malformed(String reason) {
            return new Verification(false, null, null, List.of(), null, null,
                    null, List.of(), reason);
        }
    }

    /** Top-level probe result. */
    public record ProbeResult(
            boolean ok,
            String transport,             // "BEAN" | "HTTP"
            String url,                   // populated for HTTP only
            Integer elapsedMs,
            boolean tokenReceived,
            Verification verification,
            String errorStatus,           // populated when ok=false from a transport error
            Integer httpStatus,
            String errorMessage) {

        static ProbeResult success(ChannelAuthResolverEntity row, int elapsedMs,
                                   Verification verification) {
            return new ProbeResult(true, row.getKind(), urlOf(row), elapsedMs,
                    true, verification, null, null, null);
        }

        static ProbeResult unresolved(ChannelAuthResolverEntity row, int elapsedMs) {
            return new ProbeResult(true, row.getKind(), urlOf(row), elapsedMs,
                    false, null, null, null, null);
        }

        static ProbeResult error(ChannelAuthResolverEntity row, String status,
                                 String message) {
            return error(row, status, message, null, null);
        }

        static ProbeResult error(ChannelAuthResolverEntity row, String status,
                                 String message, Integer elapsedMs, Integer httpStatus) {
            return new ProbeResult(false, row.getKind(), urlOf(row), elapsedMs,
                    false, null, status, httpStatus, message);
        }

        private static String urlOf(ChannelAuthResolverEntity row) {
            return "HTTP".equals(row.getKind()) ? "host_base_url + " + row.getHttpPath() : null;
        }
    }
}
