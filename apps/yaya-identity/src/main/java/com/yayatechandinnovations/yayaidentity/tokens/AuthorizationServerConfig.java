package com.yayatechandinnovations.yayaidentity.tokens;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.yayatechandinnovations.yayaidentity.platform.keys.KeyRing;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

/**
 * Wires Spring Authorization Server as yaya-identity's OIDC issuer, signing with
 * the platform {@link KeyRing}. This is the keystone of I1: it produces RS256
 * tokens and publishes discovery + JWKS at the standard paths, which is exactly
 * what yaya-agentic's {@code OidcAuthenticator} consumes via
 * {@code tenant_oidc_issuers} — no agent code changes (PRD M1 / AC-2).
 */
@Configuration
public class AuthorizationServerConfig {

    /** Chain 1: the OAuth2/OIDC protocol endpoints (authorize, token, jwks, userinfo, discovery). */
    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfigurer authServer =
                OAuth2AuthorizationServerConfigurer.authorizationServer();
        http
                .securityMatcher(authServer.getEndpointsMatcher())
                .with(authServer, server -> server.oidc(Customizer.withDefaults()))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                // Tokens are verified as a resource server too (introspection/self-calls).
                .oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()));
        return http.build();
    }

    /**
     * Chain 2: the token-protected API — the PDP seam and the identity admin/app
     * endpoints. A valid service/operator JWT (verified against our own JWKS) is
     * required; stateless, no session, no CSRF (it's a bearer-token JSON API).
     */
    @Bean
    @Order(2)
    SecurityFilterChain resourceServerChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/pdp/**", "/v1/identity/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(
                        org.springframework.security.config.http.SessionCreationPolicy.STATELESS));
        return http.build();
    }

    /**
     * Chain 3: everything else. Form login backs the auth-code human flow (the
     * authorization endpoint in chain 1 redirects unauthenticated users to
     * {@code /login}). The admin console tightens these rules further at I5.
     */
    @Bean
    @Order(3)
    SecurityFilterChain defaultChain(HttpSecurity http,
            com.yayatechandinnovations.yayaidentity.credentials.LoginAttemptService attempts) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/error").permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        // Track failures per submitted username for brute-force lockout (§11).
                        .successHandler((req, res, auth) -> {
                            attempts.reset(req.getParameter("username"));
                            res.sendRedirect(req.getContextPath() + "/");
                        })
                        .failureHandler((req, res, ex) -> {
                            attempts.recordFailure(req.getParameter("username"));
                            res.sendRedirect(req.getContextPath() + "/login?error");
                        }));
        return http.build();
    }

    /** SAS signs and publishes JWKS from the platform KeyRing (rotation-aware). */
    @Bean
    JWKSource<SecurityContext> jwkSource(KeyRing keyRing) {
        return new KeyRingJwkSource(keyRing);
    }

    @Bean
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    /**
     * Issuer identity. In prod set {@code yaya.identity.issuer} to the public URL
     * (the value registered in {@code tenant_oidc_issuers}). Left unset, SAS
     * derives the issuer per request — convenient for tests and multi-host dev.
     */
    @Bean
    AuthorizationServerSettings authorizationServerSettings(
            @org.springframework.beans.factory.annotation.Value("${yaya.identity.issuer:}") String issuer) {
        AuthorizationServerSettings.Builder b = AuthorizationServerSettings.builder();
        if (StringUtils.hasText(issuer)) {
            b.issuer(issuer);
        }
        return b.build();
    }
}
