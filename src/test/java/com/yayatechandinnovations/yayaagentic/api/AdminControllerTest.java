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
}
