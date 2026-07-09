package com.yayatechandinnovations.yayaidentity.tokens;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.proc.SecurityContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URL;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The I1 keystone (PRD M1 / AC-2): yaya-identity issues a verifiable RS256 token
 * and publishes OIDC discovery + JWKS. This test authenticates a client, gets a
 * token, then verifies it exactly the way a relying party (yaya-agentic's
 * {@code OidcAuthenticator}) would — resolve JWKS from discovery, verify the
 * signature against the published key, and check {@code iss}/{@code exp}. If
 * this passes, adding a {@code tenant_oidc_issuers} row is all the agent needs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TokenIssuanceIT {

    static final String CLIENT_ID = "yaya-agentic";
    static final String CLIENT_SECRET = "test-service-secret";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("yaya_identity");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("yaya.identity.secrets.encryption-key",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
        r.add("yaya.identity.bootstrap.service-client.secret", () -> CLIENT_SECRET);
    }

    @Autowired
    TestRestTemplate rest;

    @Test
    @SuppressWarnings("unchecked")
    void issuesAnRs256TokenAnyRelyingPartyCanVerify() throws Exception {
        // 1. Discovery — as a relying party would, to learn issuer + endpoints.
        Map<String, Object> discovery = rest.getForObject(
                "/.well-known/openid-configuration", Map.class);
        String issuer = (String) discovery.get("issuer");
        String tokenEndpoint = (String) discovery.get("token_endpoint");
        String jwksUri = (String) discovery.get("jwks_uri");
        assertThat(issuer).isNotBlank();
        assertThat(tokenEndpoint).endsWith("/oauth2/token");
        assertThat(jwksUri).endsWith("/oauth2/jwks");

        // 2. Client-credentials grant → a token.
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(CLIENT_ID, CLIENT_SECRET);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("scope", "identity.pdp");

        ResponseEntity<Map> tokenResp = rest.postForEntity(
                relative(tokenEndpoint), new HttpEntity<>(form, headers), Map.class);
        assertThat(tokenResp.getStatusCode().is2xxSuccessful()).isTrue();
        String accessToken = (String) tokenResp.getBody().get("access_token");
        assertThat(accessToken).isNotBlank();
        assertThat(tokenResp.getBody().get("token_type").toString()).isEqualToIgnoringCase("Bearer");

        // 3. Verify the JWT the OidcAuthenticator way: JWKS-resolved RS256 signature + iss + exp.
        //    Nimbus fetches the JWKS itself, so it needs the absolute discovery URL.
        JWKSet jwks = JWKSet.load(new URL(jwksUri));
        ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(
                JWSAlgorithm.RS256, new ImmutableJWKSet<>(jwks)));

        SignedJWT parsed = SignedJWT.parse(accessToken);
        assertThat(parsed.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(parsed.getHeader().getKeyID()).isNotBlank();       // signed by a published key

        JWTClaimsSet claims = processor.process(accessToken, null);   // throws if signature/iss invalid
        assertThat(claims.getIssuer()).isEqualTo(issuer);
        assertThat(claims.getSubject()).isEqualTo(CLIENT_ID);
        assertThat(claims.getExpirationTime()).isAfter(new Date());
    }

    @Test
    void rejectsWrongClientSecret() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(CLIENT_ID, "wrong-secret");
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");

        ResponseEntity<Map> resp = rest.postForEntity(
                "/oauth2/token", new HttpEntity<>(form, headers), Map.class);
        assertThat(resp.getStatusCode().is4xxClientError()).isTrue();
    }

    /** Discovery returns absolute URLs (http://localhost:randomPort/...); TestRestTemplate wants a root-relative path. */
    private static String relative(String absoluteUrl) {
        int schemeEnd = absoluteUrl.indexOf("://");
        int pathStart = absoluteUrl.indexOf('/', schemeEnd + 3);
        return pathStart < 0 ? "/" : absoluteUrl.substring(pathStart);
    }
}
