package com.yayatechandinnovations.yayaagentic.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.api.dto.AdminDtos;
import com.yayatechandinnovations.yayaagentic.engine.bootstrap.CatalogMapper;
import com.yayatechandinnovations.yayaagentic.operator_auth.TestAuthDance;
import com.yayatechandinnovations.yayaagentic.operator_auth.ratelimit.LoginRateLimiter;
import com.yayatechandinnovations.yayaagentic.persistence.ToolEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ToolRepository;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * The tool policy {@code secretInput} survives every way a catalogue travels:
 * installed through the admin API, read back from it, loaded into the runtime
 * catalogue, and cloned to another tenant.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
class SecretInputCatalogRoundTripTest {

    @Autowired WebTestClient client;
    @Autowired LoginRateLimiter rateLimiter;
    @Autowired ToolRepository tools;

    @Test
    void secret_input_round_trips_through_install_export_runtime_and_clone() {
        rateLimiter.resetAll();
        client = TestAuthDance.asBootstrap(client);
        String run = UUID.randomUUID().toString().substring(0, 8);
        String src = "si-src-" + run;
        String dst = "si-dst-" + run;
        tenant(src);
        tenant(dst);
        post("/v1/admin/auth-bindings", new AdminDtos.AuthBindingRequest(src, "oidc-default", "noop", List.of()));

        var handler = new AdminDtos.ToolHandlerDto("HTTP", null, new AdminDtos.HttpHandlerDto(
                "POST", "/api/ai/proposals/apply", Map.of("X-Agent-Secret", "{secret.value}"), null, null, "NONE"));
        post("/v1/admin/tools", new AdminDtos.ToolRequest(src, "proposal_apply",
                "{\"type\":\"object\"}", "{\"type\":\"object\"}", Map.of(), handler,
                Map.of("confirmable", true, "secretInput", true)));
        post("/v1/admin/tools", new AdminDtos.ToolRequest(src, "plain_read",
                "{\"type\":\"object\"}", "{\"type\":\"object\"}", Map.of(), handler, Map.of()));

        // Export: the admin API returns the policy as it was installed.
        List<AdminDtos.ToolResponse> listed = client.get().uri("/v1/admin/tools?tenant=" + src)
                .exchange().expectStatus().isOk()
                .expectBodyList(AdminDtos.ToolResponse.class).returnResult().getResponseBody();
        assertThat(listed).filteredOn(t -> t.id().equals("proposal_apply")).singleElement()
                .satisfies(t -> assertThat(t.policy()).containsEntry("secretInput", true)
                        .containsEntry("confirmable", true));

        // Runtime: the stored row becomes a descriptor that may ask for a secret.
        CatalogMapper mapper = new CatalogMapper(new ObjectMapper());
        assertThat(mapper.toDescriptor(latest(src, "proposal_apply")).policy().secretInput()).isTrue();
        assertThat(mapper.toDescriptor(latest(src, "proposal_apply")).policy().confirmable()).isTrue();
        assertThat(mapper.toDescriptor(latest(src, "plain_read")).policy().secretInput()).isFalse();

        // Clone: the destination tenant's copy keeps it.
        post("/v1/admin/capabilities", new AdminDtos.CapabilityRequest(
                src, "apply-cap", "label", "desc", "guidance", List.of("proposal_apply", "plain_read"), List.of()));
        post("/v1/admin/profiles", new AdminDtos.ProfileRequest(src, "owner", "Owner", "Hola.", "Prompt.",
                List.of("apply-cap"), "oidc-default", "es", Map.of()));
        client.post().uri("/v1/admin/tenants/" + src + "/profiles/owner/1/clone")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("destinationTenant", dst, "conflictPolicy", "FAIL",
                        "knowledgeLocationStrategy", "RETAIN", "personalityPolicy", "AUTO", "dryRun", false))
                .exchange().expectStatus().isOk();
        assertThat(mapper.toDescriptor(latest(dst, "proposal_apply")).policy().secretInput()).isTrue();
        assertThat(latest(dst, "proposal_apply").getHandlerHttpSpecJson()).contains("{secret.value}");
        assertThat(mapper.toDescriptor(latest(dst, "plain_read")).policy().secretInput()).isFalse();
    }

    private ToolEntity latest(String tenant, String id) {
        return tools.findByTenantIdAndIdOrderByVersionDesc(tenant, id).get(0);
    }

    private void tenant(String id) {
        post("/v1/admin/tenants", new AdminDtos.TenantRequest(
                id, id, "https://" + id + ".example", List.of(), List.of(), true, null, null, Map.of()));
    }

    private void post(String uri, Object body) {
        client.post().uri(uri).contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isCreated();
    }
}
