package com.yayatechandinnovations.yayaidentity.api;

import com.nimbusds.jwt.SignedJWT;
import com.yayatechandinnovations.yayaidentity.organizations.Organization;
import com.yayatechandinnovations.yayaidentity.organizations.OrganizationService;
import com.yayatechandinnovations.yayaidentity.roles.AssignmentSubject;
import com.yayatechandinnovations.yayaidentity.roles.PermissionCatalogService;
import com.yayatechandinnovations.yayaidentity.roles.Role;
import com.yayatechandinnovations.yayaidentity.roles.RoleAssignmentService;
import com.yayatechandinnovations.yayaidentity.roles.RoleService;
import com.yayatechandinnovations.yayaidentity.roles.Scope;
import com.yayatechandinnovations.yayaidentity.users.User;
import com.yayatechandinnovations.yayaidentity.users.UserService;
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
 * I4 HTTP surface: the service token is audience-bound, the PDP seam and the
 * app-permission manifest are reachable only with a valid token, and introspect
 * works. Closes the I3 gap (the {@code /pdp} + manifest endpoints).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class IdentityHttpIT {

    static final String CLIENT_ID = "yaya-agentic";
    static final String CLIENT_SECRET = "http-it-secret";

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
    @Autowired PermissionCatalogService catalog;
    @Autowired RoleService roles;
    @Autowired RoleAssignmentService assignments;
    @Autowired OrganizationService orgs;
    @Autowired UserService users;

    private String serviceToken() {
        HttpHeaders h = new HttpHeaders();
        h.setBasicAuth(CLIENT_ID, CLIENT_SECRET);
        h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("scope", "identity.pdp");
        ResponseEntity<Map> resp = rest.postForEntity("/oauth2/token", new HttpEntity<>(form, h), Map.class);
        return (String) resp.getBody().get("access_token");
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    @Test
    void serviceTokenIsAudienceBound() throws Exception {
        SignedJWT jwt = SignedJWT.parse(serviceToken());
        assertThat(jwt.getJWTClaimsSet().getAudience()).contains("yaya-sales");   // #6 / §5.6
    }

    @Test
    void pdpRequiresAToken() {
        ResponseEntity<String> resp = rest.postForEntity("/pdp/decide",
                new HttpEntity<>(Map.of("org", "o", "subject", "s", "action", "a"), jsonOnly()), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @SuppressWarnings("unchecked")
    void decideOverHttpWithAServiceToken() {
        catalog.declare("sales", List.of("sales.order:read"));
        Organization org = orgs.create("Http Org");
        Role role = roles.createRole(org.id(), "reader", List.of("sales.order:read"));
        User u = users.createPerson("http@acme.test", null, "U");
        orgs.addMember(org.id(), u.id());
        assignments.assign(org.id(), AssignmentSubject.user(u.id()), role.id(),
                new Scope.Resource("store", "st_A"));

        String token = serviceToken();
        Map<String, Object> body = Map.of("org", org.id().value(), "subject", u.id().value(),
                "action", "sales.order:read",
                "resource", Map.of("type", "store", "id", "st_A"));

        ResponseEntity<Map> permit = rest.exchange("/pdp/decide", HttpMethod.POST,
                new HttpEntity<>(body, bearer(token)), Map.class);
        assertThat(permit.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(permit.getBody().get("decision")).isEqualTo("PERMIT");

        // Effective permissions endpoint.
        ResponseEntity<List> perms = rest.exchange(
                "/pdp/permissions?org=" + org.id().value() + "&subject=" + u.id().value()
                        + "&resourceType=store&resourceId=st_A",
                HttpMethod.GET, new HttpEntity<>(bearer(token)), List.class);
        assertThat((List<String>) perms.getBody()).contains("sales.order:read");
    }

    @Test
    @SuppressWarnings("unchecked")
    void appDeclaresItsPermissionManifest() {
        String token = serviceToken();
        ResponseEntity<List> declared = rest.exchange("/v1/identity/apps/inventory/permissions",
                HttpMethod.POST, new HttpEntity<>(List.of("inventory.stock:adjust"), bearer(token)), List.class);
        assertThat((List<String>) declared.getBody()).contains("inventory.stock:adjust");
    }

    @Test
    void tokenCanBeIntrospected() {
        String token = serviceToken();
        HttpHeaders h = new HttpHeaders();
        h.setBasicAuth(CLIENT_ID, CLIENT_SECRET);
        h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("token", token);
        ResponseEntity<Map> resp = rest.postForEntity("/oauth2/introspect", new HttpEntity<>(form, h), Map.class);
        assertThat(resp.getBody().get("active")).isEqualTo(true);
    }

    private HttpHeaders jsonOnly() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}
