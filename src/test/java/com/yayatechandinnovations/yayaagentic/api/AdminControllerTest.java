package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.AdminDtos;
import com.yayatechandinnovations.yayaagentic.operator_auth.TestAuthDance;
import com.yayatechandinnovations.yayaagentic.operator_auth.ratelimit.LoginRateLimiter;
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
 * Exercises every M1 admin endpoint against the real Postgres container,
 * with one happy path + one validation-failure path per resource.
 *
 * <p>Tests are ordered so later cases can assume earlier resources exist
 * (a profile referring to a capability that the previous test created).</p>
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminControllerTest {

    @Autowired WebTestClient client;
    @Autowired LoginRateLimiter rateLimiter;

    private static final String TENANT = "admin-test";

    @BeforeEach
    void authenticateAsBootstrap() {
        // Reset the rate-limit buckets — across 10 tests we'd otherwise
        // trip the 5/min/username limit on attempt 6.
        rateLimiter.resetAll();
        // Phase 5 hardening — admin requests now also need an X-XSRF-TOKEN
        // header echoed from the XSRF-TOKEN cookie. TestAuthDance handles
        // the GET-then-POST handshake so each test sees a primed client.
        client = TestAuthDance.asBootstrap(client);
        // M2.8 — the implicit ensureTenant is gone; admin writes against an
        // unknown tenant return 400 unknown_tenant. Each test pre-registers
        // the tenant (idempotent: accepts both 201 and 409 tenant_id_taken).
        ensureAdminTestTenant();
    }

    private void ensureAdminTestTenant() {
        var req = new AdminDtos.TenantRequest(
                TENANT, "Admin Test", "https://admin-test.example",
                List.of(), List.of(), true, null, null, Map.of());
        client.post().uri("/v1/admin/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().value(s -> assertThat(s).isIn(201, 409));
    }

    @Test @Order(1)
    void posts_a_tool_then_lists_it() {
        var req = new AdminDtos.ToolRequest(
                TENANT, "noop",
                "{\"type\":\"object\"}",
                "{\"type\":\"object\"}",
                Map.of(),
                new AdminDtos.ToolHandlerDto("BEAN", "echoTool", null),
                Map.of("timeout", "PT5S"));

        var res = client.post().uri("/v1/admin/tools")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AdminDtos.ToolResponse.class)
                .returnResult().getResponseBody();

        assertThat(res).isNotNull();
        assertThat(res.id()).isEqualTo("noop");
        assertThat(res.version()).isEqualTo(1);
        assertThat(res.handler().kind()).isEqualTo("BEAN");

        List<AdminDtos.ToolResponse> list = client.get()
                .uri("/v1/admin/tools?tenant=" + TENANT)
                .exchange().expectStatus().isOk()
                .expectBodyList(AdminDtos.ToolResponse.class)
                .returnResult().getResponseBody();
        assertThat(list).extracting(AdminDtos.ToolResponse::id).contains("noop");
    }

    @Test @Order(2)
    void rejects_tool_with_unparseable_schema() {
        var bad = new AdminDtos.ToolRequest(
                TENANT, "bad", "not-json", "{}",
                Map.of(),
                new AdminDtos.ToolHandlerDto("BEAN", "echoTool", null),
                Map.of());
        client.post().uri("/v1/admin/tools")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(bad)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> assertThat(err.message()).contains("inputSchemaJson"));
    }

    @Test @Order(3)
    void posts_a_capability_referencing_the_tool() {
        var req = new AdminDtos.CapabilityRequest(
                TENANT, "do-noop", "Do a noop", "test capability",
                "Use this when the user asks for nothing.", List.of("noop"),
                List.of("do another noop"));
        var res = client.post().uri("/v1/admin/capabilities")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AdminDtos.CapabilityResponse.class)
                .returnResult().getResponseBody();
        assertThat(res).isNotNull();
        assertThat(res.tools()).containsExactly("noop");
    }

    @Test @Order(4)
    void rejects_capability_referencing_unknown_tool() {
        var req = new AdminDtos.CapabilityRequest(
                TENANT, "bad-cap", "x", null, null, List.of("nonexistent"), List.of());
        client.post().uri("/v1/admin/capabilities")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> assertThat(err.message()).contains("unknown tool"));
    }

    @Test @Order(5)
    void posts_an_auth_binding_and_then_a_profile() {
        var binding = new AdminDtos.AuthBindingRequest(
                TENANT, "dev-noop", "noop", List.of());
        client.post().uri("/v1/admin/auth-bindings")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(binding)
                .exchange().expectStatus().isCreated();

        var profile = new AdminDtos.ProfileRequest(
                TENANT, "test-profile", "Test profile",
                "Hi I'm test.", "You are a test bot.",
                List.of("do-noop"), "dev-noop", "en", Map.of());
        var res = client.post().uri("/v1/admin/profiles")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(profile)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AdminDtos.ProfileResponse.class)
                .returnResult().getResponseBody();
        assertThat(res).isNotNull();
        assertThat(res.version()).isEqualTo(1);
        assertThat(res.capabilities()).containsExactly("do-noop");
        assertThat(res.authBindingId()).isEqualTo("dev-noop");
        assertThat(res.language()).isEqualTo("en");
    }

    @Test @Order(15)
    void profile_carries_default_start_flow_id() {
        var profile = new AdminDtos.ProfileRequest(
                TENANT, "flow-bound-profile", "Flow bound",
                "Hi.", "You are a bot.",
                List.of(), null, "en", Map.of(), null, "welcome-back-demo");
        var res = client.post().uri("/v1/admin/profiles")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(profile)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AdminDtos.ProfileResponse.class)
                .returnResult().getResponseBody();
        assertThat(res).isNotNull();
        assertThat(res.defaultStartFlowId()).isEqualTo("welcome-back-demo");
    }

    @Test @Order(6)
    void second_post_to_same_profile_bumps_version() {
        var profile = new AdminDtos.ProfileRequest(
                TENANT, "test-profile", "Test profile v2",
                "Hi I'm test.", "You are a test bot, version 2.",
                List.of("do-noop"), "dev-noop", "es", Map.of());
        var res = client.post().uri("/v1/admin/profiles")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(profile)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AdminDtos.ProfileResponse.class)
                .returnResult().getResponseBody();
        assertThat(res).isNotNull();
        assertThat(res.version()).isEqualTo(2);
        assertThat(res.language())
                .as("language should follow the request body")
                .isEqualTo("es");
    }

    @Test @Order(7)
    void rejects_profile_with_unknown_auth_binding() {
        var profile = new AdminDtos.ProfileRequest(
                TENANT, "broken", "X", "X", "X",
                List.of("do-noop"), "no-such-binding", "en", Map.of());
        client.post().uri("/v1/admin/profiles")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(profile)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> assertThat(err.message()).contains("auth binding"));
    }

    @Test @Order(8)
    void posts_and_reads_a_recording_strategy() {
        var req = new AdminDtos.RecordingStrategyRequest(
                TENANT, "PROFILE", "test-profile",
                Map.of("kind", "fanout", "primary", "postgres", "sinks", List.of()));
        client.post().uri("/v1/admin/recording-strategies")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange().expectStatus().isCreated();

        var res = client.get()
                .uri("/v1/admin/recording-strategies/PROFILE/test-profile?tenant=" + TENANT)
                .exchange().expectStatus().isOk()
                .expectBody(AdminDtos.RecordingStrategyResponse.class)
                .returnResult().getResponseBody();
        assertThat(res).isNotNull();
        assertThat(res.strategy()).containsEntry("kind", "fanout");
    }

    @Test @Order(9)
    void rejects_recording_strategy_with_unknown_kind() {
        var req = new AdminDtos.RecordingStrategyRequest(
                TENANT, "TENANT", "default",
                Map.of("kind", "bogus"));
        client.post().uri("/v1/admin/recording-strategies")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> assertThat(err.message()).contains("single|fanout|tiered|classified"));
    }

    @Test @Order(10)
    void auth_available_lists_registered_authenticators() {
        var res = client.get().uri("/v1/admin/auth/available")
                .exchange().expectStatus().isOk()
                .expectBody(AdminDtos.AuthAvailability.class)
                .returnResult().getResponseBody();
        assertThat(res).isNotNull();
        assertThat(res.authenticators()).contains("noop", "oidc", "service-token", "delegated-host");
        assertThat(res.authorizers()).contains("ScopeAuthorizer", "OwnershipAuthorizer", "OpaAuthorizer");
    }

    // ---- M2.10 entity captures + bindings round-trip --------------------

    @Test @Order(11)
    void posts_a_tool_with_captures_and_bindings_and_reads_them_back() {
        // Mirrors the lottery case: prepare-draft captures draftId (SINGLETON
        // visible to the LLM, ASK_USER on conflict, rollback via cancel-draft);
        // add-lines binds draftId from memory with OVERRIDE so the LLM can't
        // forge it. See docs/design/entity-memory-design.md §10.
        var prepareDraft = new AdminDtos.ToolRequest(
                TENANT, "prepare-draft",
                "{\"type\":\"object\",\"properties\":{\"channel\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\",\"properties\":{\"draftId\":{\"type\":\"string\"}}}",
                Map.of(),
                new AdminDtos.ToolHandlerDto("BEAN", "echoTool", null),
                Map.of(),
                List.of(new com.yayatechandinnovations.yayaagentic.tool.EntityCaptureSpec(
                        "draftId", "/draftId",
                        "SESSION", "SINGLETON", "ASK_USER",
                        /* visibleToLlm */ true,
                        /* rollbackTool */ "cancel-draft",
                        /* describeTool */ null)),
                List.of());

        var created = client.post().uri("/v1/admin/tools")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(prepareDraft)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AdminDtos.ToolResponse.class)
                .returnResult().getResponseBody();
        assertThat(created).isNotNull();
        assertThat(created.captures()).hasSize(1);
        assertThat(created.captures().get(0).name()).isEqualTo("draftId");
        assertThat(created.captures().get(0).jsonPointer()).isEqualTo("/draftId");
        assertThat(created.captures().get(0).onConflict()).isEqualTo("ASK_USER");
        assertThat(created.captures().get(0).rollbackTool()).isEqualTo("cancel-draft");
        assertThat(created.captures().get(0).visibleToLlm()).isTrue();
        assertThat(created.bindings()).isEmpty();

        var addLines = new AdminDtos.ToolRequest(
                TENANT, "add-lines",
                "{\"type\":\"object\",\"properties\":{\"draftId\":{\"type\":\"string\"},"
                        + "\"numbers\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\",\"properties\":{\"lineId\":{\"type\":\"string\"}}}",
                Map.of(),
                new AdminDtos.ToolHandlerDto("BEAN", "echoTool", null),
                Map.of(),
                List.of(),
                List.of(new com.yayatechandinnovations.yayaagentic.tool.EntityBindingSpec(
                        "draftId", "draftId", "OVERRIDE")));

        var withBinding = client.post().uri("/v1/admin/tools")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(addLines)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AdminDtos.ToolResponse.class)
                .returnResult().getResponseBody();
        assertThat(withBinding).isNotNull();
        assertThat(withBinding.bindings()).hasSize(1);
        assertThat(withBinding.bindings().get(0).argName()).isEqualTo("draftId");
        assertThat(withBinding.bindings().get(0).entityName()).isEqualTo("draftId");
        assertThat(withBinding.bindings().get(0).mode()).isEqualTo("OVERRIDE");

        // Round-trip: GET list and confirm both come back with the same shape.
        List<AdminDtos.ToolResponse> list = client.get()
                .uri("/v1/admin/tools?tenant=" + TENANT)
                .exchange().expectStatus().isOk()
                .expectBodyList(AdminDtos.ToolResponse.class)
                .returnResult().getResponseBody();
        assertThat(list).isNotNull();
        AdminDtos.ToolResponse fetched = list.stream()
                .filter(t -> t.id().equals("prepare-draft")).findFirst().orElseThrow();
        assertThat(fetched.captures()).hasSize(1);
        assertThat(fetched.captures().get(0).rollbackTool()).isEqualTo("cancel-draft");
    }

    @Test @Order(12)
    void rejects_capture_with_collection_kind_but_non_append_onConflict() {
        var req = new AdminDtos.ToolRequest(
                TENANT, "bad-capture-kind",
                "{\"type\":\"object\"}", "{\"type\":\"object\"}",
                Map.of(),
                new AdminDtos.ToolHandlerDto("BEAN", "echoTool", null),
                Map.of(),
                List.of(new com.yayatechandinnovations.yayaagentic.tool.EntityCaptureSpec(
                        "lines", "/lineId",
                        "SESSION", "COLLECTION", "ASK_USER",
                        true, null, null)),
                List.of());
        client.post().uri("/v1/admin/tools")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> assertThat(err.message()).contains("COLLECTION requires onConflict=APPEND"));
    }

    @Test @Order(13)
    void rejects_binding_whose_argName_is_not_in_input_schema() {
        var req = new AdminDtos.ToolRequest(
                TENANT, "bad-binding",
                "{\"type\":\"object\",\"properties\":{\"foo\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\"}",
                Map.of(),
                new AdminDtos.ToolHandlerDto("BEAN", "echoTool", null),
                Map.of(),
                List.of(),
                List.of(new com.yayatechandinnovations.yayaagentic.tool.EntityBindingSpec(
                        "ghost", "draftId", "OVERRIDE")));
        client.post().uri("/v1/admin/tools")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> assertThat(err.message()).contains("'ghost' is not a property"));
    }

    @Test @Order(14)
    void rejects_duplicate_capture_names_on_the_same_tool() {
        var req = new AdminDtos.ToolRequest(
                TENANT, "dup-capture",
                "{\"type\":\"object\"}", "{\"type\":\"object\"}",
                Map.of(),
                new AdminDtos.ToolHandlerDto("BEAN", "echoTool", null),
                Map.of(),
                List.of(
                    new com.yayatechandinnovations.yayaagentic.tool.EntityCaptureSpec(
                        "draftId", "/a", "SESSION", "SINGLETON", "ASK_USER",
                        true, null, null),
                    new com.yayatechandinnovations.yayaagentic.tool.EntityCaptureSpec(
                        "draftId", "/b", "SESSION", "SINGLETON", "OVERWRITE",
                        true, null, null)),
                List.of());
        client.post().uri("/v1/admin/tools")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> assertThat(err.message()).contains("duplicate entity name 'draftId'"));
    }
}
