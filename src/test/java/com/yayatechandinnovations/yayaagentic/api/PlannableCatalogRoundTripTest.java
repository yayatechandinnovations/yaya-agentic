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
 * The tool policy {@code plannable} survives every way a catalogue travels:
 * installed through the admin API, read back from it, loaded into the runtime
 * catalogue, and cloned to another tenant.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
class PlannableCatalogRoundTripTest {

    @Autowired WebTestClient client;
    @Autowired LoginRateLimiter rateLimiter;
    @Autowired ToolRepository tools;

    @Test
    void plannable_round_trips_through_install_export_runtime_and_clone() {
        rateLimiter.resetAll();
        client = TestAuthDance.asBootstrap(client);
        String run = UUID.randomUUID().toString().substring(0, 8);
        String src = "pl-src-" + run;
        String dst = "pl-dst-" + run;
        tenant(src);
        tenant(dst);
        post("/v1/admin/auth-bindings", new AdminDtos.AuthBindingRequest(src, "oidc-default", "noop", List.of()));

        var handler = new AdminDtos.ToolHandlerDto("HTTP", null, new AdminDtos.HttpHandlerDto(
                "PUT", "/api/ai/sales/sale-stations/{stationCode}/sales-enabled",
                Map.of("Idempotency-Key", "{session.id}-{turn.id}-{call.id}"), null, null, "NONE"));
        post("/v1/admin/tools", new AdminDtos.ToolRequest(src, "station_sales_toggle",
                "{\"type\":\"object\"}", "{\"type\":\"object\"}", Map.of(), handler,
                Map.of("confirmable", true, "plannable", true,
                        "confirmPrompt", "¿Detener la venta en la estación {stationCode}?")));
        post("/v1/admin/tools", new AdminDtos.ToolRequest(src, "plain_read",
                "{\"type\":\"object\"}", "{\"type\":\"object\"}", Map.of(), handler, Map.of()));

        // Export: the admin API returns the policy as it was installed.
        List<AdminDtos.ToolResponse> listed = client.get().uri("/v1/admin/tools?tenant=" + src)
                .exchange().expectStatus().isOk()
                .expectBodyList(AdminDtos.ToolResponse.class).returnResult().getResponseBody();
        assertThat(listed).filteredOn(t -> t.id().equals("station_sales_toggle")).singleElement()
                .satisfies(t -> assertThat(t.policy()).containsEntry("plannable", true)
                        .containsEntry("confirmable", true));

        // Runtime: the stored row becomes a descriptor that may be a step of a plan.
        CatalogMapper mapper = new CatalogMapper(new ObjectMapper());
        assertThat(mapper.toDescriptor(latest(src, "station_sales_toggle")).policy().plannable()).isTrue();
        assertThat(mapper.toDescriptor(latest(src, "station_sales_toggle")).policy().confirmable()).isTrue();
        assertThat(mapper.toDescriptor(latest(src, "plain_read")).policy().plannable()).isFalse();

        // Clone: the destination tenant's copy keeps it.
        post("/v1/admin/capabilities", new AdminDtos.CapabilityRequest(
                src, "apply-cap", "label", "desc", "guidance", List.of("station_sales_toggle", "plain_read"), List.of()));
        post("/v1/admin/profiles", new AdminDtos.ProfileRequest(src, "owner", "Owner", "Hola.", "Prompt.",
                List.of("apply-cap"), "oidc-default", "es", Map.of()));
        client.post().uri("/v1/admin/tenants/" + src + "/profiles/owner/1/clone")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("destinationTenant", dst, "conflictPolicy", "FAIL",
                        "knowledgeLocationStrategy", "RETAIN", "personalityPolicy", "AUTO", "dryRun", false))
                .exchange().expectStatus().isOk();
        assertThat(mapper.toDescriptor(latest(dst, "station_sales_toggle")).policy().plannable()).isTrue();
        assertThat(latest(dst, "station_sales_toggle").getHandlerHttpSpecJson()).contains("{call.id}");
        assertThat(mapper.toDescriptor(latest(dst, "plain_read")).policy().plannable()).isFalse();
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
