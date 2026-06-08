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
 * End-to-end coverage of the tenant registry surface added in M2.8.
 * Exercises: registration validation, lifecycle transitions, health,
 * unknown_tenant rejection, path-only HTTP tool enforcement, and the
 * inbound origin allowlist.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TenantControllerTest {

    @Autowired WebTestClient client;
    @Autowired LoginRateLimiter rateLimiter;

    private static final String TENANT = "tenant-test";

    @BeforeEach
    void authenticate() {
        rateLimiter.resetAll();
        client = TestAuthDance.asBootstrap(client);
    }

    @Test @Order(1)
    void registers_a_new_tenant_with_required_fields() {
        var req = new AdminDtos.TenantRequest(
                TENANT, "Tenant Test", "https://tenant-test.example",
                List.of("https://*.tenant-test.example"),
                List.of("https://app.tenant-test.example"),
                true, null, null, Map.of());
        var res = client.post().uri("/v1/admin/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AdminDtos.TenantResponse.class)
                .returnResult().getResponseBody();
        assertThat(res).isNotNull();
        assertThat(res.id()).isEqualTo(TENANT);
        assertThat(res.status()).isEqualTo("ACTIVE");
        assertThat(res.hostBaseUrl()).isEqualTo("https://tenant-test.example");
        assertThat(res.hostBaseUrlAllowlist()).containsExactly("https://*.tenant-test.example");
        assertThat(res.inboundOriginAllowlist()).containsExactly("https://app.tenant-test.example");
        assertThat(res.requireHttps()).isTrue();
    }

    @Test @Order(2)
    void rejects_duplicate_tenant_id() {
        var req = new AdminDtos.TenantRequest(
                TENANT, "Dup", "https://tenant-test.example",
                List.of(), List.of(), true, null, null, Map.of());
        client.post().uri("/v1/admin/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> assertThat(err.error()).isEqualTo("tenant_id_taken"));
    }

    @Test @Order(3)
    void rejects_bad_slug() {
        var req = new AdminDtos.TenantRequest(
                "BAD-SLUG_invalid!", "x", "https://x.example",
                List.of(), List.of(), true, null, null, Map.of());
        client.post().uri("/v1/admin/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> assertThat(err.error()).isEqualTo("bad_tenant_id"));
    }

    @Test @Order(4)
    void rejects_missing_host_base_url() {
        var req = new AdminDtos.TenantRequest(
                "missing-host", "x", null,
                List.of(), List.of(), true, null, null, Map.of());
        client.post().uri("/v1/admin/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> assertThat(err.error()).isEqualTo("bad_host_base_url"));
    }

    @Test @Order(5)
    void rejects_http_host_when_require_https_true() {
        var req = new AdminDtos.TenantRequest(
                "http-host", "x", "http://insecure.example",
                List.of(), List.of(), true, null, null, Map.of());
        client.post().uri("/v1/admin/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> assertThat(err.message()).contains("must be https"));
    }

    @Test @Order(6)
    void permits_http_host_when_require_https_false() {
        var req = new AdminDtos.TenantRequest(
                "http-ok", "Plain HTTP allowed", "http://plain.example",
                List.of(), List.of(), false, null, null, Map.of());
        client.post().uri("/v1/admin/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isCreated();
    }

    @Test @Order(7)
    void rejects_unknown_default_auth_binding() {
        var req = new AdminDtos.TenantRequest(
                "no-binding", "x", "https://x.example",
                List.of(), List.of(), true,
                "ghost", null, Map.of());
        client.post().uri("/v1/admin/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> assertThat(err.error()).isEqualTo("unknown_auth_binding"));
    }

    @Test @Order(8)
    void suspend_resume_archive_lifecycle() {
        client.post().uri("/v1/admin/tenants/" + TENANT + "/suspend")
                .exchange().expectStatus().isOk()
                .expectBody(AdminDtos.TenantResponse.class)
                .value(r -> assertThat(r.status()).isEqualTo("SUSPENDED"));

        client.post().uri("/v1/admin/tenants/" + TENANT + "/resume")
                .exchange().expectStatus().isOk()
                .expectBody(AdminDtos.TenantResponse.class)
                .value(r -> assertThat(r.status()).isEqualTo("ACTIVE"));
    }

    @Test @Order(9)
    void health_reports_missing_host_base_url_on_default_tenant() {
        // The bootstrap "default" tenant has no host_base_url after V9 backfill.
        var res = client.get().uri("/v1/admin/tenants/default/health")
                .exchange().expectStatus().isOk()
                .expectBody(AdminDtos.TenantHealthResponse.class)
                .returnResult().getResponseBody();
        assertThat(res).isNotNull();
        assertThat(res.hostBaseUrlSet()).isFalse();
        assertThat(res.warnings()).anyMatch(w -> w.contains("hostBaseUrl is not set"));
    }

    @Test @Order(10)
    void admin_writes_against_unknown_tenant_are_rejected() {
        var req = new AdminDtos.ToolRequest(
                "ghost-tenant", "x",
                "{\"type\":\"object\"}", "{\"type\":\"object\"}",
                Map.of(),
                new AdminDtos.ToolHandlerDto("BEAN", "echoTool", null),
                Map.of());
        client.post().uri("/v1/admin/tools")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> assertThat(err.error()).isEqualTo("unknown_tenant"));
    }

    @Test @Order(11)
    void http_tool_with_absolute_url_is_rejected() {
        var httpSpec = new AdminDtos.HttpHandlerDto(
                "GET", "https://api.tenant-test.example/v1/orders",
                Map.of(), null, null, "NONE");
        var req = new AdminDtos.ToolRequest(
                TENANT, "absolute-tool",
                "{\"type\":\"object\"}", "{\"type\":\"object\"}",
                Map.of(),
                new AdminDtos.ToolHandlerDto("HTTP", null, httpSpec),
                Map.of());
        client.post().uri("/v1/admin/tools")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> {
                    assertThat(err.error()).isEqualTo("absolute_url_not_permitted");
                    assertThat(err.message()).contains("path");
                });
    }

    @Test @Order(12)
    void http_tool_with_protocol_relative_is_rejected() {
        var httpSpec = new AdminDtos.HttpHandlerDto(
                "GET", "//api.example/x",
                Map.of(), null, null, "NONE");
        var req = new AdminDtos.ToolRequest(
                TENANT, "protorel-tool",
                "{\"type\":\"object\"}", "{\"type\":\"object\"}",
                Map.of(),
                new AdminDtos.ToolHandlerDto("HTTP", null, httpSpec),
                Map.of());
        client.post().uri("/v1/admin/tools")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> assertThat(err.error()).isEqualTo("absolute_url_not_permitted"));
    }

    @Test @Order(13)
    void http_tool_with_path_only_template_is_accepted() {
        var httpSpec = new AdminDtos.HttpHandlerDto(
                "GET", "/v1/orders/{id}",
                Map.of(), null, null, "NONE");
        var req = new AdminDtos.ToolRequest(
                TENANT, "path-tool",
                "{\"type\":\"object\"}", "{\"type\":\"object\"}",
                Map.of(),
                new AdminDtos.ToolHandlerDto("HTTP", null, httpSpec),
                Map.of());
        client.post().uri("/v1/admin/tools")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isCreated();
    }

    @Test @Order(14)
    void inbound_origin_outside_allowlist_returns_403() {
        // Tenant has inboundOriginAllowlist=[https://app.tenant-test.example]
        // (set in test #1). The CORS filter allows http://localhost:* by default,
        // so we pick a localhost origin — CORS lets it through, OriginEnforcer
        // denies it because it isn't in the tenant's allowlist.
        var result = client.post().uri("/v1/sessions")
                .header("Origin", "http://localhost:9876")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("tenant", TENANT, "channel", "web"))
                .exchange()
                .expectStatus().isEqualTo(403)
                .expectBody(AdminDtos.ApiError.class)
                .returnResult().getResponseBody();
        assertThat(result).isNotNull();
        assertThat(result.error()).isEqualTo("origin_not_permitted");
    }

    @Test @Order(15)
    void inbound_request_without_origin_header_is_not_blocked_by_filter() {
        // No Origin header → server-to-server pattern, filter is skipped.
        // The session itself will fail validation (no profile registered for
        // tenant-test), but we only assert the origin filter didn't fire.
        var res = client.post().uri("/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("tenant", TENANT, "channel", "web"))
                .exchange()
                .expectBody(AdminDtos.ApiError.class)
                .returnResult();
        if (res.getStatus().value() == 403) {
            assertThat(res.getResponseBody())
                    .extracting(AdminDtos.ApiError::error)
                    .isNotEqualTo("origin_not_permitted");
        }
    }
}
