package com.yayatechandinnovations.yayaagentic.auth.oidc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.auth.AuthenticationException;
import com.yayatechandinnovations.yayaagentic.auth.Authenticator;
import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.persistence.TenantOidcIssuerEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantOidcIssuerRepository;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.text.ParseException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * JWT bearer authenticator. Recognises {@code Authorization: Bearer <jwt>},
 * verifies signature against the issuer's JWKS, asserts {@code iss}/{@code aud}/
 * {@code exp}/{@code nbf}, and produces a {@link Principal} with scopes
 * resolved from the {@code scope} / {@code scp} claims.
 *
 * <h3>Where trusted issuers come from</h3>
 *
 * <p>Two sources, in priority order:</p>
 * <ol>
 *   <li><b>Per-tenant rows in {@code tenant_oidc_issuers}</b> — first-class.
 *       An operator registers a tenant's IdP through
 *       {@code POST /v1/admin/tenants/{id}/oidc-issuers}; no backend restart.
 *       Cached in-memory per tenant; evicted by
 *       {@link TenantOidcIssuersChangedEvent} on every write.</li>
 *   <li><b>Global YAML at {@code yaya.agentic.auth.oidc.issuers}</b> — kept
 *       as a fallback for ops who prefer file-based config. Loaded once at
 *       startup; used when no per-tenant row matches the JWT's {@code iss}.</li>
 * </ol>
 *
 * <p>If a JWT is presented and neither source recognises its {@code iss},
 * this fails closed with an {@link AuthenticationException} that names the
 * untrusted issuer and lists the currently trusted ones (so a typo /
 * trailing-slash mismatch is debuggable from one log line).</p>
 */
@Component
@Order(100)
public class OidcAuthenticator implements Authenticator {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(OidcAuthenticator.class);

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final YayaAgenticProperties props;
    private final TenantOidcIssuerRepository tenantIssuers;
    private final ObjectMapper json;

    /** YAML-sourced (global) issuers. Frozen at startup. */
    private final Map<String, CachedIssuer> globalIssuers = new ConcurrentHashMap<>();

    /** DB-sourced issuers, keyed by tenantId → (iss → CachedIssuer). Lazily
     *  populated per tenant on first auth attempt; evicted by
     *  {@link TenantOidcIssuersChangedEvent}. */
    private final Map<String, Map<String, CachedIssuer>> tenantIssuersCache = new ConcurrentHashMap<>();

    private record CachedIssuer(ConfigurableJWTProcessor<SecurityContext> processor,
                                Set<String> audiences) {}

    public OidcAuthenticator(YayaAgenticProperties props,
                             TenantOidcIssuerRepository tenantIssuers,
                             ObjectMapper json) {
        this.props = props;
        this.tenantIssuers = tenantIssuers;
        this.json = json;
        if (props.auth() != null && props.auth().oidc() != null
                && props.auth().oidc().issuers() != null) {
            for (var issuer : props.auth().oidc().issuers()) {
                ConfigurableJWTProcessor<SecurityContext> processor =
                        buildProcessor(issuer.iss(), issuer.jwksUri());
                Set<String> auds = issuer.audiences() == null
                        ? Set.of() : Set.copyOf(issuer.audiences());
                globalIssuers.put(issuer.iss(), new CachedIssuer(processor, auds));
            }
        }
        if (globalIssuers.isEmpty()) {
            LOG.info("OidcAuthenticator: no YAML issuers — per-tenant rows in tenant_oidc_issuers "
                    + "are the only trust source. Register one via POST /v1/admin/tenants/{id}/oidc-issuers.");
        } else {
            LOG.info("OidcAuthenticator: {} YAML fallback issuer(s) trusted globally: {}",
                    globalIssuers.size(), globalIssuers.keySet());
        }
    }

    @Override
    public String name() { return "oidc"; }

    @Override
    public Optional<Principal> tryAuthenticate(AuthContext ctx) throws AuthenticationException {
        String header = ctx.headers() == null ? null : ctx.headers().get("Authorization");
        if (header == null) header = ctx.headers() == null ? null : ctx.headers().get("authorization");
        if (header == null) {
            LOG.debug("oidc skip: no Authorization header on request");
            return Optional.empty();
        }
        if (!header.startsWith("Bearer ")) {
            LOG.debug("oidc skip: Authorization header is not a Bearer scheme (got '{}')",
                    header.split(" ", 2)[0]);
            return Optional.empty();
        }
        String token = header.substring(7).trim();

        SignedJWT signed;
        String iss;
        String alg;
        try {
            signed = (SignedJWT) JWTParser.parse(token);
            iss = signed.getJWTClaimsSet().getIssuer();
            alg = signed.getHeader().getAlgorithm() == null
                    ? "" : signed.getHeader().getAlgorithm().getName();
        } catch (ParseException | ClassCastException e) {
            LOG.debug("oidc skip: Bearer token is not a parseable JWT — letting the chain continue "
                    + "(token prefix='{}', err={})", safePrefix(token), e.getMessage());
            return Optional.empty();
        }
        // HS* JWTs belong to SharedSecretJwtAuthenticator — we trust only
        // asymmetric algs against JWKS-published keys here.
        if (alg != null && alg.startsWith("HS")) {
            LOG.debug("oidc skip: alg='{}' is HMAC — yielding to shared-secret JWT authenticator", alg);
            return Optional.empty();
        }
        if (iss == null) throw new AuthenticationException("oidc: token has no iss claim");

        String tenantId = ctx.tenant() == null ? null : ctx.tenant().value();
        CachedIssuer cached = lookupIssuer(tenantId, iss);
        if (cached == null) {
            throw new AuthenticationException(
                    "oidc: untrusted issuer '" + iss + "' for tenant '" + tenantId
                            + "' (trusted: " + trustedNow(tenantId) + ")");
        }

        try {
            JWTClaimsSet claims = cached.processor().process(signed, null);
            if (!cached.audiences().isEmpty()
                    && (claims.getAudience() == null
                        || claims.getAudience().stream().noneMatch(cached.audiences()::contains))) {
                throw new AuthenticationException("oidc: audience not allowed (token aud="
                        + claims.getAudience() + ", expected one of " + cached.audiences() + ")");
            }
            return Optional.of(toPrincipal(ctx, claims));
        } catch (AuthenticationException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthenticationException("oidc: token validation failed", e);
        }
    }

    // ---- Tenant cache --------------------------------------------------

    private CachedIssuer lookupIssuer(String tenantId, String iss) {
        if (tenantId != null) {
            Map<String, CachedIssuer> tenantMap = tenantIssuersCache.computeIfAbsent(
                    tenantId, this::loadTenantIssuers);
            CachedIssuer hit = tenantMap.get(iss);
            if (hit != null) return hit;
        }
        return globalIssuers.get(iss);
    }

    private Map<String, CachedIssuer> loadTenantIssuers(String tenantId) {
        Map<String, CachedIssuer> built = new ConcurrentHashMap<>();
        for (TenantOidcIssuerEntity e : tenantIssuers.findByTenantId(tenantId)) {
            ConfigurableJWTProcessor<SecurityContext> processor =
                    buildProcessor(e.getIss(), e.getJwksUri());
            built.put(e.getIss(), new CachedIssuer(processor, parseAudiences(e.getAudiencesJson())));
        }
        return built;
    }

    @EventListener
    public void onTenantOidcIssuersChanged(TenantOidcIssuersChangedEvent event) {
        tenantIssuersCache.remove(event.tenantId());
        LOG.info("OidcAuthenticator: evicted per-tenant issuer cache for '{}'", event.tenantId());
    }

    private Set<String> trustedNow(String tenantId) {
        Set<String> out = new HashSet<>(globalIssuers.keySet());
        if (tenantId != null) {
            tenantIssuers.findByTenantId(tenantId).forEach(e -> out.add(e.getIss()));
        }
        return out;
    }

    private Set<String> parseAudiences(String audsJson) {
        if (audsJson == null || audsJson.isBlank()) return Set.of();
        try {
            List<String> list = json.readValue(audsJson, STRING_LIST);
            return list == null || list.isEmpty() ? Set.of() : Set.copyOf(list);
        } catch (Exception e) {
            LOG.warn("oidc: failed to parse audiences JSON '{}' — treating as no audience check", audsJson);
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

    // ---- Processor construction ---------------------------------------

    private ConfigurableJWTProcessor<SecurityContext> buildProcessor(String iss, String jwksUri) {
        try {
            URL jwksUrl = new URL(jwksUri);
            Duration ttl = props.auth() != null && props.auth().oidc() != null
                    && props.auth().oidc().jwksCacheTtl() != null
                    ? props.auth().oidc().jwksCacheTtl()
                    : Duration.ofMinutes(10);
            JWKSource<SecurityContext> keySource = JWKSourceBuilder
                    .create(jwksUrl)
                    .cache(ttl.toMillis(), Duration.ofSeconds(30).toMillis())
                    .build();
            ConfigurableJWTProcessor<SecurityContext> p = new DefaultJWTProcessor<>();
            p.setJWSKeySelector(new JWSVerificationKeySelector<>(
                    Set.of(JWSAlgorithm.RS256, JWSAlgorithm.RS384, JWSAlgorithm.RS512,
                            JWSAlgorithm.ES256, JWSAlgorithm.ES384, JWSAlgorithm.ES512),
                    keySource));
            return p;
        } catch (Exception e) {
            throw new IllegalStateException("oidc: failed to wire JWKS for " + iss + " (" + jwksUri + ")", e);
        }
    }

    /** First 12 chars of the token, for log lines. Never the whole thing. */
    private static String safePrefix(String token) {
        if (token == null) return "";
        return token.length() <= 12 ? token : token.substring(0, 12) + "…";
    }
}
