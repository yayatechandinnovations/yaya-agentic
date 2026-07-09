package com.yayatechandinnovations.yayaidentity.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Make-before-break key rotation over HTTP (AC-3 / NFR-Avail-3): after rotating,
 * JWKS carries BOTH the new ACTIVE and the RETIRING key, so tokens signed before
 * the rotation still verify.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class KeyRotationIT {

    static final String CLIENT_ID = "yaya-agentic";
    static final String CLIENT_SECRET = "rotate-it-secret";

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

    @Autowired TestRestTemplate rest;

    @Test
    @SuppressWarnings("unchecked")
    void rotationKeepsTheOldKeyInJwks() {
        List<String> before = kids();
        assertThat(before).hasSize(1);

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token());
        Map<String, Object> rotated = rest.exchange("/v1/identity/admin/keys/rotate",
                HttpMethod.POST, new HttpEntity<>(h), Map.class).getBody();
        String newKid = (String) rotated.get("activeKid");

        List<String> after = kids();
        assertThat(after).contains(newKid);          // new ACTIVE published
        assertThat(after).contains(before.get(0));   // old key RETIRING, still verifiable
        assertThat(after).hasSize(2);
    }

    @SuppressWarnings("unchecked")
    private List<String> kids() {
        Map<String, Object> jwks = rest.getForObject("/oauth2/jwks", Map.class);
        return ((List<Map<String, Object>>) jwks.get("keys")).stream()
                .map(k -> (String) k.get("kid")).toList();
    }

    private String token() {
        HttpHeaders h = new HttpHeaders();
        h.setBasicAuth(CLIENT_ID, CLIENT_SECRET);
        h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("scope", "identity.pdp");
        return (String) rest.postForEntity("/oauth2/token", new HttpEntity<>(form, h), Map.class)
                .getBody().get("access_token");
    }
}
