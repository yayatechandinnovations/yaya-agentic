package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.AdminDtos;
import com.yayatechandinnovations.yayaagentic.operator_auth.TestAuthDance;
import com.yayatechandinnovations.yayaagentic.operator_auth.ratelimit.LoginRateLimiter;
import com.yayatechandinnovations.yayaagentic.persistence.PersonalityFragmentEntity;
import com.yayatechandinnovations.yayaagentic.persistence.PersonalityFragmentRepository;
import com.yayatechandinnovations.yayaagentic.persistence.ProfileRepository;
import com.yayatechandinnovations.yayaagentic.persistence.ToolEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ToolRepository;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * End-to-end coverage of the cross-tenant profile clone (§7) and the
 * absolute→path migrator (§6, §9.2).
 *
 * <p>Pre-seeds a source tenant with a profile + capability + path-only HTTP
 * tool, then exercises every clone policy axis. Tests are ordered because
 * each one builds on the state the previous one created.</p>
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CloneServiceTest {

    @Autowired WebTestClient client;
    @Autowired LoginRateLimiter rateLimiter;
    @Autowired ToolRepository tools;
    @Autowired ProfileRepository profiles;
    @Autowired PersonalityFragmentRepository personality;

    private static final String SRC = "clone-src";
    private static final String DST = "clone-dst";
    private static final String EU  = "clone-eu";

    @BeforeEach
    void seed() {
        rateLimiter.resetAll();
        client = TestAuthDance.asBootstrap(client);
        ensureTenant(SRC, "https://api.clone-src.example");
        ensureTenant(DST, "https://api.clone-dst.example");
        ensureTenant(EU,  "https://eu.api.clone-src.example");
        ensureAuthBinding(SRC, "oidc-default");
        ensureAuthBinding(DST, "oidc-default");          // pre-existing at dst
        // EU intentionally has no binding — covers the CREATE branch.
        ensureBeanTool(SRC, "echoTool", "echo");
        ensurePathTool(SRC, "list-orders", "/v1/orders/{id}");
        ensureCapability(SRC, "do-stuff", List.of("echo", "list-orders"));
        ensureProfile(SRC, "support-agent", List.of("do-stuff"), "oidc-default");
    }

    @Test @Order(1)
    void dry_run_returns_a_plan_without_writing() {
        var body = Map.of(
                "destinationTenant", DST,
                "conflictPolicy", "FAIL",
                "knowledgeLocationStrategy", "RETAIN",
                "personalityPolicy", "AUTO",
                "dryRun", true);
        var res = client.post().uri("/v1/admin/tenants/" + SRC + "/profiles/support-agent/1/clone")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult().getResponseBody();

        assertThat(res).isNotNull();
        assertThat(res.get("status")).isEqualTo("DRY_RUN");
        @SuppressWarnings("unchecked")
        Map<String, Object> plan = (Map<String, Object>) res.get("plan");
        assertThat(plan).isNotNull();
        assertThat(plan.get("destinationTenant")).isEqualTo(DST);
        assertThat(plan.get("destinationProfileId")).isEqualTo("support-agent");
        @SuppressWarnings("unchecked")
        Map<String, Object> profileAction = (Map<String, Object>) plan.get("profile");
        assertThat(profileAction.get("action")).isEqualTo("CREATE_NEW_VERSION");
        // Nothing should have been written to the destination yet.
        assertThat(profiles.findByTenantIdAndIdOrderByVersionDesc(DST, "support-agent")).isEmpty();
    }

    @Test @Order(2)
    void apply_writes_resources_at_destination_with_path_only_url_preserved() {
        var body = Map.of(
                "destinationTenant", EU,
                "conflictPolicy", "FAIL",
                "knowledgeLocationStrategy", "RETAIN",
                "personalityPolicy", "AUTO",
                "dryRun", false);
        var res = client.post().uri("/v1/admin/tenants/" + SRC + "/profiles/support-agent/1/clone")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult().getResponseBody();
        assertThat(res).isNotNull();
        assertThat(res.get("status")).isEqualTo("APPLIED");

        // Profile + capability + tool exist at EU with version 1.
        assertThat(profiles.findByTenantIdAndIdOrderByVersionDesc(EU, "support-agent"))
                .extracting(p -> p.getVersion())
                .containsExactly(1);
        ToolEntity dstTool = tools.findByTenantIdAndIdOrderByVersionDesc(EU, "list-orders").get(0);
        assertThat(dstTool.getHandlerKind()).isEqualTo("HTTP");
        // Path-only urlTemplate survives the clone verbatim — host resolves
        // from the destination tenant's host_base_url at dispatch time.
        assertThat(dstTool.getHandlerHttpSpecJson()).contains("/v1/orders/{id}");
        // No tenant host in the descriptor — host resolves at dispatch time.
        assertThat(dstTool.getHandlerHttpSpecJson()).doesNotContain("clone-src");
        // Auth binding was created at EU because it didn't pre-exist.
        var euBinding = client.get().uri("/v1/admin/auth-bindings?tenant=" + EU)
                .exchange().expectStatus().isOk()
                .expectBodyList(AdminDtos.AuthBindingResponse.class)
                .returnResult().getResponseBody();
        assertThat(euBinding).isNotNull();
        assertThat(euBinding).extracting(AdminDtos.AuthBindingResponse::id).contains("oidc-default");
    }

    @Test @Order(3)
    void fail_policy_rejects_when_destination_already_has_resource() {
        // DST already has oidc-default from seeding. The capability/tool/profile
        // don't yet exist there, but the *first* clone will land them. A second
        // clone with FAIL should then fail.
        var first = Map.of(
                "destinationTenant", DST,
                "conflictPolicy", "FAIL",
                "knowledgeLocationStrategy", "RETAIN",
                "personalityPolicy", "AUTO",
                "dryRun", false);
        client.post().uri("/v1/admin/tenants/" + SRC + "/profiles/support-agent/1/clone")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(first)
                .exchange().expectStatus().isOk();

        client.post().uri("/v1/admin/tenants/" + SRC + "/profiles/support-agent/1/clone")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(first)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> assertThat(err.error()).isEqualTo("destination_resource_exists"));
    }

    @Test @Order(4)
    void new_version_policy_bumps_existing_destination_resources() {
        var body = Map.of(
                "destinationTenant", DST,
                "conflictPolicy", "NEW_VERSION",
                "knowledgeLocationStrategy", "RETAIN",
                "personalityPolicy", "AUTO",
                "dryRun", false);
        client.post().uri("/v1/admin/tenants/" + SRC + "/profiles/support-agent/1/clone")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange().expectStatus().isOk();
        // Destination now has profile v2 (v1 came from test #3).
        assertThat(profiles.findByTenantIdAndIdOrderByVersionDesc(DST, "support-agent"))
                .extracting(p -> p.getVersion())
                .contains(2);
    }

    @Test @Order(5)
    void same_tenant_source_destination_is_rejected() {
        var body = Map.of(
                "destinationTenant", SRC,
                "conflictPolicy", "FAIL",
                "knowledgeLocationStrategy", "RETAIN",
                "personalityPolicy", "AUTO",
                "dryRun", true);
        client.post().uri("/v1/admin/tenants/" + SRC + "/profiles/support-agent/1/clone")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> assertThat(err.error()).isEqualTo("clone_same_tenant"));
    }

    @Test @Order(6)
    void unknown_source_profile_returns_404() {
        var body = Map.of(
                "destinationTenant", DST,
                "conflictPolicy", "FAIL",
                "knowledgeLocationStrategy", "RETAIN",
                "personalityPolicy", "AUTO",
                "dryRun", true);
        client.post().uri("/v1/admin/tenants/" + SRC + "/profiles/no-such-profile/1/clone")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> assertThat(err.error()).isEqualTo("unknown_profile"));
    }

    @Test @Order(7)
    void personality_never_requires_destination_to_have_one() {
        var body = Map.of(
                "destinationTenant", DST,
                "conflictPolicy", "NEW_VERSION",
                "knowledgeLocationStrategy", "RETAIN",
                "personalityPolicy", "NEVER",
                "dryRun", true);
        client.post().uri("/v1/admin/tenants/" + SRC + "/profiles/support-agent/1/clone")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> assertThat(err.error()).isEqualTo("destination_missing_personality"));
    }

    @Test @Order(8)
    void personality_auto_creates_at_destination_when_missing() {
        // Seed a personality fragment at the source.
        personality.save(new PersonalityFragmentEntity(SRC, "en", "warm, concise", 1));

        var body = Map.of(
                "destinationTenant", EU,
                "conflictPolicy", "NEW_VERSION",
                "knowledgeLocationStrategy", "RETAIN",
                "personalityPolicy", "AUTO",
                "dryRun", false);
        var res = client.post().uri("/v1/admin/tenants/" + SRC + "/profiles/support-agent/1/clone")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange().expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult().getResponseBody();
        assertThat(res).isNotNull();
        assertThat(res.get("status")).isEqualTo("APPLIED");

        // Destination EU now has an 'en' personality fragment.
        assertThat(personality.findLatestForLocale(EU, "en")).isPresent();
    }

    // ---- helpers --------------------------------------------------------

    private void ensureTenant(String id, String hostBaseUrl) {
        var req = new AdminDtos.TenantRequest(
                id, id, hostBaseUrl, List.of(), List.of(), true, null, null, Map.of());
        client.post().uri("/v1/admin/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().value(s -> assertThat(s).isIn(201, 409));
    }

    private void ensureAuthBinding(String tenant, String id) {
        var req = new AdminDtos.AuthBindingRequest(tenant, id, "noop", List.of());
        client.post().uri("/v1/admin/auth-bindings")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().value(s -> assertThat(s).isIn(201, 409));
    }

    private void ensureBeanTool(String tenant, String beanName, String toolId) {
        if (!tools.findByTenantIdAndIdOrderByVersionDesc(tenant, toolId).isEmpty()) return;
        var req = new AdminDtos.ToolRequest(
                tenant, toolId,
                "{\"type\":\"object\"}", "{\"type\":\"object\"}",
                Map.of(),
                new AdminDtos.ToolHandlerDto("BEAN", beanName, null),
                Map.of());
        client.post().uri("/v1/admin/tools")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange().expectStatus().isCreated();
    }

    private void ensurePathTool(String tenant, String toolId, String path) {
        if (!tools.findByTenantIdAndIdOrderByVersionDesc(tenant, toolId).isEmpty()) return;
        var spec = new AdminDtos.HttpHandlerDto(
                "GET", path, Map.of(), null, null, "NONE");
        var req = new AdminDtos.ToolRequest(
                tenant, toolId,
                "{\"type\":\"object\"}", "{\"type\":\"object\"}",
                Map.of(),
                new AdminDtos.ToolHandlerDto("HTTP", null, spec),
                Map.of());
        client.post().uri("/v1/admin/tools")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange().expectStatus().isCreated();
    }

    private void ensureCapability(String tenant, String id, List<String> toolIds) {
        var req = new AdminDtos.CapabilityRequest(
                tenant, id, "label", "desc", "guidance", toolIds, List.of());
        client.post().uri("/v1/admin/capabilities")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().value(s -> assertThat(s).isIn(201, 400));   // 400 if already exists with same id (next-version bump still creates)
    }

    private void ensureProfile(String tenant, String id, List<String> capabilityIds, String bindingId) {
        if (!profiles.findByTenantIdAndIdOrderByVersionDesc(tenant, id).isEmpty()) return;
        var req = new AdminDtos.ProfileRequest(
                tenant, id, "Support Agent",
                "Hi I'm here to help.", "You are a helpful support agent.",
                capabilityIds, bindingId, "en", Map.of());
        client.post().uri("/v1/admin/profiles")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange().expectStatus().isCreated();
    }
}
