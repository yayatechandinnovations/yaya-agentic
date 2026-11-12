package com.yayatechandinnovations.yayaagentic.structured;

import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.SignedJWT;
import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.auth.AuthenticationException;
import com.yayatechandinnovations.yayaagentic.auth.jwt.SharedSecretJwtAuthenticator;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.persistence.TenantJwtSecretEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantJwtSecretRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Admission for {@code POST /v1/structured-completions}: a per-tenant HS256
 * service JWT, and nothing else.
 *
 * <p>The endpoint has no tenant in its path or body, so the tenant comes from
 * the token: the (unverified) {@code iss} selects the {@code tenant_jwt_secrets}
 * rows that trust that issuer, and each candidate tenant is then verified by
 * {@link SharedSecretJwtAuthenticator} — the same signature / {@code exp} /
 * {@code nbf} / audience checks and the same decrypted key cache the session
 * path uses. The first tenant whose key verifies the token is the caller's
 * tenant. An attacker choosing {@code iss} gains nothing: without that tenant's
 * key the signature check fails.</p>
 *
 * <p>Talking to {@code SharedSecretJwtAuthenticator} directly — not the
 * {@code AuthenticatorChain} — is deliberate: the anonymous fallback
 * ({@code yaya.agentic.auth.allow-anonymous}), service tokens, OIDC and API
 * keys can never admit a request here.</p>
 */
@Component
public class StructuredCompletionAuthenticator {

    public static final String SCOPE = "structured:complete";
    public static final String AUDIENCE = "yaya-agentic";

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(StructuredCompletionAuthenticator.class);

    private final TenantJwtSecretRepository secrets;
    private final SharedSecretJwtAuthenticator jwt;

    public StructuredCompletionAuthenticator(TenantJwtSecretRepository secrets, SharedSecretJwtAuthenticator jwt) {
        this.secrets = secrets;
        this.jwt = jwt;
    }

    /**
     * Blocking (JPA). Returns the verified principal, whose {@code tenant()} is
     * the resolved tenant.
     *
     * @throws StructuredCompletionException 401 for a missing / malformed /
     *         unverifiable token, 403 for a valid token without {@link #SCOPE}
     */
    public Principal authenticate(String authorizationHeader) {
        String iss = issuerOf(authorizationHeader);
        Set<String> tenants = new LinkedHashSet<>();
        for (TenantJwtSecretEntity row : secrets.findByIss(iss)) tenants.add(row.getTenantId());

        Map<String, String> headers = Map.of("Authorization", authorizationHeader);
        Principal principal = null;
        for (String tenant : tenants) {
            try {
                Optional<Principal> hit = jwt.tryAuthenticate(
                        new AuthContext(new Ids.TenantId(tenant), headers, Optional.empty()));
                if (hit.isPresent()) {
                    principal = hit.get();
                    break;
                }
            } catch (AuthenticationException e) {
                LOG.debug("structured-completion auth: tenant '{}' rejected the token: {}", tenant, e.getMessage());
            }
        }
        if (principal == null) throw StructuredCompletionException.unauthorized();

        // A service credential must be bounded and minted for this service —
        // stricter than the session path, which tolerates both being absent.
        Map<String, Object> claims = principal.claims();
        if (claims.get("exp") == null || !audienceMatches(claims.get("aud"))) {
            throw StructuredCompletionException.unauthorized();
        }
        if (!principal.scopes().contains(SCOPE)) {
            throw StructuredCompletionException.forbidden("token lacks scope " + SCOPE);
        }
        return principal;
    }

    /** The token's unverified issuer — only ever used to pick candidate keys. */
    private static String issuerOf(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw StructuredCompletionException.unauthorized();
        }
        try {
            SignedJWT signed = (SignedJWT) JWTParser.parse(authorizationHeader.substring(7).trim());
            var alg = signed.getHeader().getAlgorithm();
            String iss = signed.getJWTClaimsSet().getIssuer();
            if (alg == null || !alg.getName().startsWith("HS") || iss == null || iss.isBlank()) {
                throw StructuredCompletionException.unauthorized();
            }
            return iss;
        } catch (java.text.ParseException | ClassCastException e) {
            // Not a signed JWT at all — API keys (yk_live_…) and opaque tokens end here.
            throw StructuredCompletionException.unauthorized();
        }
    }

    private static boolean audienceMatches(Object aud) {
        if (aud instanceof Collection<?> list) return list.contains(AUDIENCE);
        return AUDIENCE.equals(aud);
    }
}
