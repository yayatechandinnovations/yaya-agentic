package com.yayatechandinnovations.yayaidentity.platform.keys;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * I0 exit criterion: the app boots on its own {@code yaya_identity} schema
 * (Flyway migrates), bootstraps an ACTIVE RS256 signing key, and serves a valid
 * public JWKS at {@code /oauth2/jwks} — the seam relying parties verify against.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class JwksSmokeIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("yaya_identity");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        // Root-of-trust: a real master key so the signing-key private material is encrypted at rest.
        r.add("yaya.identity.secrets.encryption-key",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    KeyRing keyRing;

    @Test
    @SuppressWarnings("unchecked")
    void servesAPublicJwksWithABootstrappedRs256Key() {
        Map<String, Object> body = rest.getForObject("/oauth2/jwks", Map.class);

        assertThat(body).containsKey("keys");
        List<Map<String, Object>> keys = (List<Map<String, Object>>) body.get("keys");
        assertThat(keys).hasSize(1);

        Map<String, Object> jwk = keys.get(0);
        assertThat(jwk).containsEntry("kty", "RSA");
        assertThat(jwk).containsEntry("alg", "RS256");
        assertThat(jwk).containsEntry("use", "sig");
        assertThat(jwk).containsKey("kid");
        assertThat(jwk).doesNotContainKey("d");   // never leak the private exponent
    }

    @Test
    void activeKeyIsUsableForSigning() {
        SigningKey active = keyRing.active();
        assertThat(active.status()).isEqualTo(SigningKey.Status.ACTIVE);
        assertThat(active.privateKey()).isNotNull();   // decrypted from the AES-GCM envelope
        assertThat(active.alg()).isEqualTo("RS256");
    }
}
