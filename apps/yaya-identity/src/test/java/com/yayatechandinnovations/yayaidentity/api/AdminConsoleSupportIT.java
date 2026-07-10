package com.yayatechandinnovations.yayaidentity.api;

import com.yayatechandinnovations.yayaidentity.organizations.OrganizationService;
import com.yayatechandinnovations.yayaidentity.platform.Ids.OrgId;
import com.yayatechandinnovations.yayaidentity.platform.Ids.UserId;
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
 * The console-facing shape of the admin API: org profiles (create-with-details,
 * edit, member counts), people-not-ids member listings, group membership CRUD,
 * the permission catalog, and role listings that include system roles with their
 * permissions — everything the console's pickers and detail views stand on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AdminConsoleSupportIT {

    static final String CLIENT_ID = "yaya-agentic";
    static final String CLIENT_SECRET = "console-support-it-secret";

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
    @Autowired OrganizationService organizations;

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

    private HttpHeaders bearer() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token());
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    @Test
    void orgProfileIsCreatableEditableAndCountsMembers() {
        HttpHeaders h = bearer();

        Map<String, Object> created = post("/v1/identity/admin/orgs", Map.of(
                "name", "Acme Foods",
                "description", "Wholesale groceries",
                "contactEmail", "ops@acme.test",
                "contactPhone", "+18095551234",
                "website", "https://acme.test",
                "country", "DO"), h);
        String org = (String) created.get("id");
        assertThat(created.get("description")).isEqualTo("Wholesale groceries");
        assertThat(created.get("contactEmail")).isEqualTo("ops@acme.test");
        assertThat(created.get("status")).isEqualTo("ACTIVE");
        assertThat(created.get("memberCount")).isEqualTo(0);

        // Edit: name + profile change, slug stays the immutable handle.
        Map<String, Object> updated = rest.exchange("/v1/identity/admin/orgs/" + org, HttpMethod.PUT,
                new HttpEntity<>(Map.of("name", "Acme Foods Intl",
                        "description", "Regional wholesale", "country", "US"), h), Map.class).getBody();
        assertThat(updated.get("name")).isEqualTo("Acme Foods Intl");
        assertThat(updated.get("description")).isEqualTo("Regional wholesale");
        assertThat(updated.get("country")).isEqualTo("US");
        assertThat(updated.get("slug")).isEqualTo(created.get("slug"));
        assertThat(updated.get("contactEmail")).isNull(); // full replace, not merge

        // A blank name is rejected.
        ResponseEntity<Map> bad = rest.exchange("/v1/identity/admin/orgs/" + org, HttpMethod.PUT,
                new HttpEntity<>(Map.of("name", " "), h), Map.class);
        assertThat(bad.getStatusCode().value()).isEqualTo(400);

        // Membership shows up in the count and in the enriched member listing.
        String userId = (String) post("/v1/identity/admin/users",
                Map.of("email", "clerk@acme.test", "displayName", "Clerk One"), h).get("id");
        organizations.addMember(new OrgId(org), new UserId(userId));

        Map<String, Object> reread = rest.exchange("/v1/identity/admin/orgs/" + org,
                HttpMethod.GET, new HttpEntity<>(h), Map.class).getBody();
        assertThat(reread.get("memberCount")).isEqualTo(1);

        List<Map<String, Object>> members = rest.exchange("/v1/identity/admin/orgs/" + org + "/members",
                HttpMethod.GET, new HttpEntity<>(h), List.class).getBody();
        assertThat(members).hasSize(1);
        assertThat(members.get(0).get("email")).isEqualTo("clerk@acme.test");
        assertThat(members.get(0).get("displayName")).isEqualTo("Clerk One");
        assertThat(members.get(0).get("user")).isEqualTo(userId);
    }

    @Test
    void groupMembershipIsListableAndRemovable() {
        HttpHeaders h = bearer();

        String org = (String) post("/v1/identity/admin/orgs", Map.of("name", "Group Co"), h).get("id");
        String userId = (String) post("/v1/identity/admin/users",
                Map.of("email", "grouped@acme.test", "displayName", "Grouped"), h).get("id");
        String gid = (String) post("/v1/identity/admin/orgs/" + org + "/groups",
                Map.of("name", "Cashiers"), h).get("id");

        rest.exchange("/v1/identity/admin/groups/" + gid + "/members/" + userId,
                HttpMethod.PUT, new HttpEntity<>(h), Map.class);

        List<Map<String, Object>> groups = rest.exchange("/v1/identity/admin/orgs/" + org + "/groups",
                HttpMethod.GET, new HttpEntity<>(h), List.class).getBody();
        assertThat(groups).anySatisfy(g -> {
            assertThat(g.get("id")).isEqualTo(gid);
            assertThat(g.get("memberCount")).isEqualTo(1);
        });

        List<Map<String, Object>> members = rest.exchange("/v1/identity/admin/groups/" + gid + "/members",
                HttpMethod.GET, new HttpEntity<>(h), List.class).getBody();
        assertThat(members).hasSize(1);
        assertThat(members.get(0).get("email")).isEqualTo("grouped@acme.test");

        rest.exchange("/v1/identity/admin/groups/" + gid + "/members/" + userId,
                HttpMethod.DELETE, new HttpEntity<>(h), Map.class);
        List<Map<String, Object>> after = rest.exchange("/v1/identity/admin/groups/" + gid + "/members",
                HttpMethod.GET, new HttpEntity<>(h), List.class).getBody();
        assertThat(after).isEmpty();
    }

    @Test
    void roleListingsCarrySystemRolesPermissionsAndTheCatalog() {
        HttpHeaders h = bearer();

        String org = (String) post("/v1/identity/admin/orgs", Map.of("name", "Role Co"), h).get("id");
        rest.exchange("/v1/identity/apps/sales/permissions", HttpMethod.POST,
                new HttpEntity<>(List.of("sales.order:read", "sales.order:refund"), h), List.class);
        post("/v1/identity/admin/orgs/" + org + "/roles",
                Map.of("name", "refunder", "permissions", List.of("sales.order:refund")), h);

        List<String> catalog = rest.exchange("/v1/identity/admin/permissions",
                HttpMethod.GET, new HttpEntity<>(h), List.class).getBody();
        assertThat(catalog).contains("sales.order:read", "sales.order:refund");

        List<Map<String, Object>> roleList = rest.exchange("/v1/identity/admin/orgs/" + org + "/roles",
                HttpMethod.GET, new HttpEntity<>(h), List.class).getBody();
        // System roles are assignable, so the console must see them alongside org roles.
        assertThat(roleList).anySatisfy(r -> {
            assertThat(r.get("name")).isEqualTo("owner");
            assertThat(r.get("system")).isEqualTo(true);
        });
        assertThat(roleList).anySatisfy(r -> {
            assertThat(r.get("name")).isEqualTo("refunder");
            assertThat(r.get("system")).isEqualTo(false);
            assertThat((List<String>) r.get("permissions")).containsExactly("sales.order:refund");
        });
    }

    @Test
    void policyListingIsRichAndTheActiveToggleGovernsThePdp() {
        HttpHeaders h = bearer();

        String org = (String) post("/v1/identity/admin/orgs", Map.of("name", "Policy Co"), h).get("id");
        rest.exchange("/v1/identity/apps/sales/permissions", HttpMethod.POST,
                new HttpEntity<>(List.of("sales.order:refund"), h), List.class);
        String roleId = (String) post("/v1/identity/admin/orgs/" + org + "/roles",
                Map.of("name", "refunder", "permissions", List.of("sales.order:refund")), h).get("id");
        String userId = (String) post("/v1/identity/admin/users",
                Map.of("email", "refunder@acme.test", "displayName", "Refunder"), h).get("id");
        rest.exchange("/v1/identity/admin/orgs/" + org + "/role-assignments", HttpMethod.POST,
                new HttpEntity<>(Map.of("subjectKind", "USER", "subjectId", userId, "roleId", roleId), h),
                Map.class);
        Map<String, Object> decideBody = Map.of("org", org, "subject", userId,
                "action", "sales.order:refund");
        assertThat(post("/pdp/decide", decideBody, h).get("decision")).isEqualTo("PERMIT");

        // A DENY policy built the way the console builder does it.
        Map<String, Object> created = post("/v1/identity/admin/orgs/" + org + "/policies", Map.of(
                "name", "refund-freeze",
                "effect", "DENY",
                "target", Map.of("actions", List.of("sales.order:refund")),
                "priority", 100,
                "userReason", "Refunds are paused right now.",
                "auditReason", "ops incident 4711 — refunds frozen"), h);
        String policyId = (String) created.get("id");
        assertThat(created.get("active")).isEqualTo(true);
        assertThat(post("/pdp/decide", decideBody, h).get("decision")).isEqualTo("DENY");

        // The list carries everything the console cards render.
        List<Map<String, Object>> listed = rest.exchange("/v1/identity/admin/orgs/" + org + "/policies",
                HttpMethod.GET, new HttpEntity<>(h), List.class).getBody();
        assertThat(listed).anySatisfy(p -> {
            assertThat(p.get("name")).isEqualTo("refund-freeze");
            assertThat((String) p.get("targetJson")).contains("sales.order:refund");
            assertThat(p.get("userReason")).isEqualTo("Refunds are paused right now.");
            assertThat(p.get("auditReason")).isEqualTo("ops incident 4711 — refunds frozen");
            assertThat(p.get("active")).isEqualTo(true);
        });

        // Deactivating (not deleting) lifts the freeze; the policy stays listed.
        Map<String, Object> toggled = rest.exchange(
                "/v1/identity/admin/orgs/" + org + "/policies/" + policyId + "/active",
                HttpMethod.PUT, new HttpEntity<>(Map.of("active", false), h), Map.class).getBody();
        assertThat(toggled.get("active")).isEqualTo(false);
        assertThat(post("/pdp/decide", decideBody, h).get("decision")).isEqualTo("PERMIT");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Object body, HttpHeaders h) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, h), Map.class).getBody();
    }
}
