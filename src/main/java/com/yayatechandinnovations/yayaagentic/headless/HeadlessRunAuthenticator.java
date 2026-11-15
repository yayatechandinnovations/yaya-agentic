package com.yayatechandinnovations.yayaagentic.headless;

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
 * Admission for {@code /v1/headless/runs}: a per-tenant HS256 service JWT with
 * scope {@link #SCOPE}, and nothing else — the scheme of
 * {@code StructuredCompletionAuthenticator}.
 *
 * <p>The endpoints have no tenant in their path or body, so the tenant comes
 * from the token: the (unverified) {@code iss} selects the
 * {@code tenant_jwt_secrets} rows that trust that issuer, and each candidate
 * tenant is then verified by {@link SharedSecretJwtAuthenticator} — signature,
 * {@code exp}, {@code nbf}, audience and claim pins. The first tenant whose
 * key verifies the token is the caller's tenant; choosing an {@code iss}
 * gains nothing without that tenant's key.</p>
 *
 * <p>The {@code AuthenticatorChain} is deliberately not consulted: the
 * anonymous fallback, service tokens, OIDC and API keys can never admit a
 * request here.</p>
 */
@Component
public class HeadlessRunAuthenticator {

    public static final String SCOPE = "headless:run";
    public static final String AUDIENCE = "yaya-agentic";

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(HeadlessRunAuthenticator.class);

    private final TenantJwtSecretRepository secrets;
    private final SharedSecretJwtAuthenticator jwt;

    public HeadlessRunAuthenticator(TenantJwtSecretRepository secrets, SharedSecretJwtAuthenticator jwt) {
        this.secrets = secrets;
        this.jwt = jwt;
    }

    /**
     * Blocking (JPA). Returns the verified service principal, whose
     * {@code tenant()} is the resolved tenant.
     *
     * @throws HeadlessRunException 401 {@code unauthorized} for a missing,
     *         malformed or unverifiable token, or one without {@code exp} or
     *         the audience; 403 {@code forbidden} for a valid token without
     *         {@link #SCOPE}
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
                LOG.debug("headless-run auth: tenant '{}' rejected the token: {}", tenant, e.getMessage());
            }
        }
        if (principal == null) throw HeadlessRunException.unauthorized();

        // A service credential must be bounded and minted for this service.
        Map<String, Object> claims = principal.claims();
        if (claims.get("exp") == null || !audienceMatches(claims.get("aud"))) {
            throw HeadlessRunException.unauthorized();
        }
        if (!principal.scopes().contains(SCOPE)) {
            throw HeadlessRunException.forbidden("token lacks scope " + SCOPE);
        }
        return principal;
    }

    /** The token's unverified issuer — only ever used to pick candidate keys. */
    private static String issuerOf(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw HeadlessRunException.unauthorized();
        }
        try {
            SignedJWT signed = (SignedJWT) JWTParser.parse(authorizationHeader.substring(7).trim());
            var alg = signed.getHeader().getAlgorithm();
            String iss = signed.getJWTClaimsSet().getIssuer();
            if (alg == null || !alg.getName().startsWith("HS") || iss == null || iss.isBlank()) {
                throw HeadlessRunException.unauthorized();
            }
            return iss;
        } catch (java.text.ParseException | ClassCastException e) {
            // Not a signed JWT at all — API keys and opaque tokens end here.
            throw HeadlessRunException.unauthorized();
        }
    }

    private static boolean audienceMatches(Object aud) {
        if (aud instanceof Collection<?> list) return list.contains(AUDIENCE);
        return AUDIENCE.equals(aud);
    }
}
