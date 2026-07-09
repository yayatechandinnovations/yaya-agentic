package com.yayatechandinnovations.yayaidentity.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
 * The admin API drives the whole model over HTTP (design §9.3): create org →
 * declare permission → role → user → scoped assignment, and the PDP then sees the
 * admin-created grant. Audit read returns the recorded decision.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AdminApiIT {

    static final String CLIENT_ID = "yaya-agentic";
    static final String CLIENT_SECRET = "admin-it-secret";

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

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    @Test
    @SuppressWarnings("unchecked")
    void adminBuildsTheModelAndThePdpHonorsIt() {
        String token = token();
        HttpHeaders h = bearer(token);

        String org = (String) post("/v1/identity/admin/orgs", Map.of("name", "Admin Co"), h).get("id");
        // Manifest declare returns a JSON array, not an object.
        rest.exchange("/v1/identity/apps/sales/permissions", HttpMethod.POST,
                new HttpEntity<>(List.of("sales.order:read"), h), List.class);
        String roleId = (String) post("/v1/identity/admin/orgs/" + org + "/roles",
                Map.of("name", "reader", "permissions", List.of("sales.order:read")), h).get("id");
        String userId = (String) post("/v1/identity/admin/users",
                Map.of("email", "admin-made@acme.test", "displayName", "Made"), h).get("id");
        // Membership + scoped assignment.
        rest.exchange("/v1/identity/admin/orgs/" + org + "/role-assignments", HttpMethod.POST,
                new HttpEntity<>(Map.of("subjectKind", "USER", "subjectId", userId,
                        "roleId", roleId, "scopeType", "store", "scopeId", "st_A"), h), Map.class);

        // The PDP now honors the admin-created scoped grant.
        Map<String, Object> decide = post("/pdp/decide", Map.of("org", org, "subject", userId,
                "action", "sales.order:read", "resource", Map.of("type", "store", "id", "st_A")), h);
        assertThat(decide.get("decision")).isEqualTo("PERMIT");

        // MFA toggle + a service client via the admin API.
        rest.exchange("/v1/identity/admin/orgs/" + org + "/mfa", HttpMethod.PUT,
                new HttpEntity<>(Map.of("required", true), h), Map.class);

        // Audit read returns the decision we just made.
        ResponseEntity<List> audit = rest.exchange("/v1/identity/admin/orgs/" + org + "/audit",
                HttpMethod.GET, new HttpEntity<>(h), List.class);
        assertThat((List<Map<String, Object>>) audit.getBody())
                .anySatisfy(row -> assertThat(row.get("action")).isEqualTo("sales.order:read"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Object body, HttpHeaders h) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, h), Map.class).getBody();
    }
}
