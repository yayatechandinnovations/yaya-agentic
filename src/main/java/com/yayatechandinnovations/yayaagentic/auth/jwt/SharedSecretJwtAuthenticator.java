package com.yayatechandinnovations.yayaagentic.auth.jwt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.SignedJWT;
import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.auth.AuthenticationException;
import com.yayatechandinnovations.yayaagentic.auth.Authenticator;
import com.yayatechandinnovations.yayaagentic.auth.secrets.JwtSecretCipher;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.persistence.TenantJwtSecretEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantJwtSecretRepository;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.text.ParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * HS256 / HS384 / HS512 bearer authenticator. Validates JWTs signed with
 * a per-tenant HMAC secret pulled from {@code tenant_jwt_secrets} —
 * the path for integrating an internal identity service that doesn't
 * speak OIDC (no JWKS, no asymmetric keys).
 *
 * <h3>Trust model</h3>
 * <p>yaya-agentic holds the HMAC key, so it can both sign and verify
 * tokens for the configured tenant — same posture as
 * {@code ServiceTokenAuthenticator}. The upside vs. service-token is
 * that we still extract a per-user principal from the verified payload
 * instead of stamping every call with a tenant-level identity. Keys
 * are AES-256-GCM-encrypted at rest via {@link JwtSecretCipher}.</p>
 *
 * <h3>Chain ordering</h3>
 * <p>{@code @Order(150)} — sits between {@code OidcAuthenticator(100)}
 * and {@code ServiceTokenAuthenticator(200)}. Both JWT authenticators
 * yield based on the {@code alg} header: OIDC yields on HS-family algs,
 * this one yields on RS / ES asymmetric algs, so a tenant can mix both
 * (e.g. internal app via HS256, B2B partner via RS256) without collisions.</p>
 */
@Component
@Order(150)
public class SharedSecretJwtAuthenticator implements Authenticator {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(SharedSecretJwtAuthenticator.class);

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final TenantJwtSecretRepository repo;
    private final JwtSecretCipher cipher;
    private final ObjectMapper json;

    /** Cache: tenantId → (iss → CachedSecret). Lazily populated per
     *  tenant; evicted by {@link TenantJwtSecretsChangedEvent}. */
    private final Map<String, Map<String, CachedSecret>> cache = new ConcurrentHashMap<>();

    private record CachedSecret(MACVerifier verifier, Set<String> audiences) {}

    public SharedSecretJwtAuthenticator(TenantJwtSecretRepository repo,
                                        JwtSecretCipher cipher,
                                        ObjectMapper json) {
        this.repo = repo;
        this.cipher = cipher;
        this.json = json;
    }

    @Override
    public String name() { return "shared-secret-jwt"; }

    @Override
    public Optional<Principal> tryAuthenticate(AuthContext ctx) throws AuthenticationException {
        String header = ctx.headers() == null ? null : ctx.headers().get("Authorization");
        if (header == null) header = ctx.headers() == null ? null : ctx.headers().get("authorization");
        if (header == null) {
            LOG.debug("shared-secret-jwt skip: no Authorization header");
            return Optional.empty();
        }
        if (!header.startsWith("Bearer ")) {
            LOG.debug("shared-secret-jwt skip: not Bearer scheme");
            return Optional.empty();
        }
        String token = header.substring(7).trim();

        SignedJWT signed;
        String alg;
        String iss;
        try {
            signed = (SignedJWT) JWTParser.parse(token);
            alg = signed.getHeader().getAlgorithm() == null
                    ? "" : signed.getHeader().getAlgorithm().getName();
            iss = signed.getJWTClaimsSet().getIssuer();
        } catch (ParseException | ClassCastException e) {
            LOG.debug("shared-secret-jwt skip: token is not a parseable JWT (err={})", e.getMessage());
            return Optional.empty();
        }

        // Only handle HS* algorithms — yield to OidcAuthenticator (or
        // any future asymmetric JWT authenticator) for everything else.
        if (alg == null || !alg.startsWith("HS")) {
            LOG.debug("shared-secret-jwt skip: alg='{}' is not HS-family", alg);
            return Optional.empty();
        }
        if (iss == null) throw new AuthenticationException("shared-secret-jwt: token has no iss claim");

        String tenantId = ctx.tenant() == null ? null : ctx.tenant().value();
        CachedSecret hit = lookup(tenantId, iss);
        if (hit == null) {
            throw new AuthenticationException(
                    "shared-secret-jwt: untrusted issuer '" + iss + "' for tenant '" + tenantId
                            + "' (trusted: " + trustedNow(tenantId) + ")");
        }

        try {
            if (!signed.verify(hit.verifier())) {
                throw new AuthenticationException("shared-secret-jwt: signature mismatch");
            }
            JWTClaimsSet claims = signed.getJWTClaimsSet();

            // Honour exp / nbf with no clock skew — same posture as
            // OidcAuthenticator's default processor.
            Date now = new Date();
            if (claims.getExpirationTime() != null && now.after(claims.getExpirationTime())) {
                throw new AuthenticationException("shared-secret-jwt: token expired");
            }
            if (claims.getNotBeforeTime() != null && now.before(claims.getNotBeforeTime())) {
                throw new AuthenticationException("shared-secret-jwt: token not yet valid");
            }
            if (!hit.audiences().isEmpty()
                    && (claims.getAudience() == null
                        || claims.getAudience().stream().noneMatch(hit.audiences()::contains))) {
                throw new AuthenticationException("shared-secret-jwt: audience not allowed (token aud="
                        + claims.getAudience() + ", expected one of " + hit.audiences() + ")");
            }
            return Optional.of(toPrincipal(ctx, claims));
        } catch (AuthenticationException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthenticationException("shared-secret-jwt: token validation failed", e);
        }
    }

    // ---- Cache ---------------------------------------------------------

    private CachedSecret lookup(String tenantId, String iss) {
        if (tenantId == null) return null;
        Map<String, CachedSecret> tenantMap = cache.computeIfAbsent(tenantId, this::loadTenant);
        return tenantMap.get(iss);
    }

    private Map<String, CachedSecret> loadTenant(String tenantId) {
        Map<String, CachedSecret> built = new ConcurrentHashMap<>();
        for (TenantJwtSecretEntity row : repo.findByTenantId(tenantId)) {
            try {
                String secret = cipher.decrypt(row.getSecretCiphertext(), row.getSecretIv());
                MACVerifier verifier = new MACVerifier(
                        secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                built.put(row.getIss(),
                        new CachedSecret(verifier, parseAudiences(row.getAudiencesJson())));
            } catch (Exception e) {
                // One bad row shouldn't poison the whole tenant — log and skip.
                // The admin endpoint surfaces decrypt failures on read; the
                // operator can re-key or delete the bad row.
                LOG.error("shared-secret-jwt: failed to load row id={} iss='{}' for tenant '{}': {}",
                        row.getId(), row.getIss(), tenantId, e.getMessage());
            }
        }
        return built;
    }

    @EventListener
    public void onTenantJwtSecretsChanged(TenantJwtSecretsChangedEvent event) {
        cache.remove(event.tenantId());
        LOG.info("shared-secret-jwt: evicted per-tenant cache for '{}'", event.tenantId());
    }

    private Set<String> trustedNow(String tenantId) {
        if (tenantId == null) return Set.of();
        Set<String> out = new HashSet<>();
        repo.findByTenantId(tenantId).forEach(e -> out.add(e.getIss()));
        return out;
    }

    private Set<String> parseAudiences(String audsJson) {
        if (audsJson == null || audsJson.isBlank()) return Set.of();
        try {
            List<String> list = json.readValue(audsJson, STRING_LIST);
            return list == null || list.isEmpty() ? Set.of() : Set.copyOf(list);
        } catch (Exception e) {
            LOG.warn("shared-secret-jwt: failed to parse audiences JSON '{}' — treating as no aud check",
                    audsJson);
            return Set.of();
        }
    }

    // ---- JWT → Principal ----------------------------------------------

    private Principal toPrincipal(AuthContext ctx, JWTClaimsSet claims) {
        String subject = claims.getSubject() != null ? claims.getSubject() : "unknown";
        Set<String> scopes = resolveScopes(claims);
        Map<String, Object> claimMap = claims.getClaims();
        Ids.TenantId tenant = ctx.tenant() != null ? ctx.tenant() : new Ids.TenantId("default");
        return new Principal(subject, tenant, scopes, Map.copyOf(claimMap),
                claims.getIssueTime() == null ? java.time.Instant.now() : claims.getIssueTime().toInstant());
    }

    private Set<String> resolveScopes(JWTClaimsSet claims) {
        Set<String> out = new HashSet<>();
        Object scopeClaim = claims.getClaim("scope");
        if (scopeClaim instanceof String s) {
            Stream.of(s.split("\\s+")).filter(p -> !p.isBlank()).forEach(out::add);
        }
        Object scpClaim = claims.getClaim("scp");
        if (scpClaim instanceof List<?> list) {
            list.stream().filter(Objects::nonNull).map(Object::toString).forEach(out::add);
        }
        return out.isEmpty() ? Set.of() : Set.copyOf(out);
    }

    /** Exposed for tests — the chain's actual alg matcher uses
     *  {@link JWSAlgorithm#HS256} etc. internally via Nimbus. */
    static boolean isHsAlg(String alg) {
        return alg != null && alg.startsWith("HS");
    }
}
