package com.yayatechandinnovations.yayaagentic.auth.oidc;

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
 * <p>Trusted issuers (and their JWKS URIs / accepted audiences) live in
 * {@code yaya.agentic.auth.oidc.issuers}. Empty list = OIDC disabled.</p>
 */
@Component
@Order(100)
public class OidcAuthenticator implements Authenticator {

    private final Map<String, ConfigurableJWTProcessor<SecurityContext>> processorsByIssuer = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> audiencesByIssuer = new ConcurrentHashMap<>();
    private final YayaAgenticProperties props;

    public OidcAuthenticator(YayaAgenticProperties props) {
        this.props = props;
        if (props.auth() != null && props.auth().oidc() != null
                && props.auth().oidc().issuers() != null) {
            for (var issuer : props.auth().oidc().issuers()) {
                processorsByIssuer.put(issuer.iss(), buildProcessor(issuer));
                audiencesByIssuer.put(issuer.iss(), issuer.audiences() == null
                        ? Set.of() : Set.copyOf(issuer.audiences()));
            }
        }
    }

    @Override
    public String name() { return "oidc"; }

    @Override
    public Optional<Principal> tryAuthenticate(AuthContext ctx) throws AuthenticationException {
        if (processorsByIssuer.isEmpty()) return Optional.empty();
        String header = ctx.headers() == null ? null : ctx.headers().get("Authorization");
        if (header == null) header = ctx.headers() == null ? null : ctx.headers().get("authorization");
        if (header == null || !header.startsWith("Bearer ")) return Optional.empty();
        String token = header.substring(7).trim();

        try {
            SignedJWT signed = (SignedJWT) JWTParser.parse(token);
            String iss = signed.getJWTClaimsSet().getIssuer();
            if (iss == null) throw new AuthenticationException("oidc: missing iss");

            ConfigurableJWTProcessor<SecurityContext> processor = processorsByIssuer.get(iss);
            if (processor == null) {
                // Recognised as a JWT but issuer is not trusted — fail closed.
                throw new AuthenticationException("oidc: untrusted issuer " + iss);
            }
            JWTClaimsSet claims = processor.process(signed, null);

            Set<String> expectedAudiences = audiencesByIssuer.getOrDefault(iss, Set.of());
            if (!expectedAudiences.isEmpty()
                    && (claims.getAudience() == null
                        || claims.getAudience().stream().noneMatch(expectedAudiences::contains))) {
                throw new AuthenticationException("oidc: audience not allowed");
            }
            return Optional.of(toPrincipal(ctx, claims));
        } catch (ParseException e) {
            return Optional.empty();   // not actually a JWT — let other authenticators try
        } catch (AuthenticationException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthenticationException("oidc: token validation failed", e);
        }
    }

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

    private ConfigurableJWTProcessor<SecurityContext> buildProcessor(
            YayaAgenticProperties.Auth.Oidc.Issuer issuer) {
        try {
            URL jwksUrl = new URL(issuer.jwksUri());
            Duration ttl = props.auth().oidc().jwksCacheTtl() != null
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
            throw new IllegalStateException("oidc: failed to wire JWKS for " + issuer.iss(), e);
        }
    }
}
