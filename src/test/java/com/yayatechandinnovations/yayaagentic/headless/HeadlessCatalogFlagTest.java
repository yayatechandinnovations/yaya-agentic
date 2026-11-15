package com.yayatechandinnovations.yayaagentic.headless;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.api.dto.AdminDtos;
import com.yayatechandinnovations.yayaagentic.engine.bootstrap.CatalogMapper;
import com.yayatechandinnovations.yayaagentic.operator_auth.TestAuthDance;
import com.yayatechandinnovations.yayaagentic.operator_auth.ratelimit.LoginRateLimiter;
import com.yayatechandinnovations.yayaagentic.persistence.ToolEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ToolRepository;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import com.yayatechandinnovations.yayaagentic.tool.ToolDescriptor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * {@code policy.headless} ({@code docs/guides/admin-panel-sessions.md} §17)
 * through the catalogue: accepted on a plain HTTP GET, refused with
 * {@code headless_not_allowed} on anything that writes or waits for a person,
 * and kept by install, export, the runtime and a clone.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
class HeadlessCatalogFlagTest {

    private static final String SCHEMA = "{\"type\":\"object\"}";

    @Autowired WebTestClient client;
    @Autowired LoginRateLimiter rateLimiter;
    @Autowired ToolRepository tools;

    private final CatalogMapper mapper = new CatalogMapper(new ObjectMapper());

    @Test
    void headless_round_trips_through_install_export_runtime_and_clone() {
        rateLimiter.resetAll();
        client = TestAuthDance.asBootstrap(client);
        String run = UUID.randomUUID().toString().substring(0, 8);
        String src = "hl-src-" + run;
        String dst = "hl-dst-" + run;
        tenant(src);
        tenant(dst);
        post("/v1/admin/auth-bindings", new AdminDtos.AuthBindingRequest(src, "oidc-default", "noop", List.of()))
                .expectStatus().isCreated();

        tool(src, "report_sales_summary", http("GET"), Map.of("headless", true)).expectStatus().isCreated();
        tool(src, "customer_find", http("GET"), Map.of()).expectStatus().isCreated();
        tool(src, "customer_show", http("GET"), Map.of("headless", false)).expectStatus().isCreated();

        // Export: the admin API returns the policy as it was installed.
        List<AdminDtos.ToolResponse> listed = client.get().uri("/v1/admin/tools?tenant=" + src)
                .exchange().expectStatus().isOk()
                .expectBodyList(AdminDtos.ToolResponse.class).returnResult().getResponseBody();
        assertThat(listed).filteredOn(t -> t.id().equals("report_sales_summary")).singleElement()
                .satisfies(t -> assertThat(t.policy()).containsEntry("headless", true));
        assertThat(listed).filteredOn(t -> t.id().equals("customer_find")).singleElement()
                .satisfies(t -> assertThat(t.policy()).doesNotContainKey("headless"));

        // Runtime: only the flagged row becomes a descriptor a headless run is offered.
        assertThat(mapper.toDescriptor(latest(src, "report_sales_summary")).policy().headless()).isTrue();
        assertThat(mapper.toDescriptor(latest(src, "customer_find")).policy().headless()).isFalse();
        assertThat(mapper.toDescriptor(latest(src, "customer_show")).policy().headless()).isFalse();

        // Clone: the destination tenant's copy keeps it.
        post("/v1/admin/capabilities", new AdminDtos.CapabilityRequest(src, "reports-cap", "label", "desc",
                "guidance", List.of("report_sales_summary", "customer_find"), List.of())).expectStatus().isCreated();
        post("/v1/admin/profiles", new AdminDtos.ProfileRequest(src, "owner", "Owner", "Hola.", "Prompt.",
                List.of("reports-cap"), "oidc-default", "es", Map.of())).expectStatus().isCreated();
        client.post().uri("/v1/admin/tenants/" + src + "/profiles/owner/1/clone")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("destinationTenant", dst, "conflictPolicy", "FAIL",
                        "knowledgeLocationStrategy", "RETAIN", "personalityPolicy", "AUTO", "dryRun", false))
                .exchange().expectStatus().isOk();
        assertThat(mapper.toDescriptor(latest(dst, "report_sales_summary")).policy().headless()).isTrue();
        assertThat(mapper.toDescriptor(latest(dst, "customer_find")).policy().headless()).isFalse();
    }

    @Test
    void headless_is_refused_on_a_tool_that_writes_or_waits_for_a_person() {
        rateLimiter.resetAll();
        client = TestAuthDance.asBootstrap(client);
        String tenant = "hl-rule-" + UUID.randomUUID().toString().substring(0, 8);
        tenant(tenant);

        Map<String, Map<String, Object>> refusedPolicies = new LinkedHashMap<>();
        refusedPolicies.put("confirmable", Map.of("headless", true, "confirmable", true));
        refusedPolicies.put("secretInput", Map.of("headless", true, "secretInput", true));
        refusedPolicies.put("clientInvocable", Map.of("headless", true, "clientInvocable", true));
        refusedPolicies.put("plannable", Map.of("headless", true, "plannable", true));
        refusedPolicies.put("preconfirmableByAction", Map.of("headless", true, "preconfirmableByAction", true));
        refusedPolicies.forEach((flag, policy) -> refused(tool(tenant, "bad_" + flag.toLowerCase(), http("GET"), policy)));

        // memory_save and report_export are POSTs; a write is a write whatever its policy says.
        for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
            refused(tool(tenant, "bad_" + method.toLowerCase(), http(method), Map.of("headless", true)));
        }
        refused(tool(tenant, "bad_bean", new AdminDtos.ToolHandlerDto("BEAN", "echoTool", null),
                Map.of("headless", true)));

        // Nothing of that was stored, and the same tools are accepted without the flag.
        assertThat(tools.findByTenantId(tenant)).isEmpty();
        tool(tenant, "bad_post", http("POST"), Map.of()).expectStatus().isCreated();
        tool(tenant, "bad_confirmable", http("GET"), Map.of("confirmable", true)).expectStatus().isCreated();
        tool(tenant, "good_get", http("GET"), Map.of("headless", true, "idempotent", true, "timeout", "PT30S"))
                .expectStatus().isCreated();
        assertThat(mapper.toDescriptor(latest(tenant, "good_get")).policy().headless()).isTrue();
    }

    @Test
    void a_stored_row_flagged_against_the_rule_is_registered_without_the_flag() {
        // Straight into the table, as a row written before the rule or by hand would be.
        ToolEntity post = new ToolEntity("default", "legacy_export", 1, SCHEMA, SCHEMA, "HTTP");
        post.setHandlerHttpSpecJson("{\"method\":\"POST\",\"urlTemplate\":\"/api/ai/export\","
                + "\"headerTemplates\":{},\"authForwarding\":\"PRINCIPAL_TOKEN\"}");
        post.setPolicyJson("{\"headless\":true}");
        ToolEntity confirmable = new ToolEntity("default", "legacy_toggle", 1, SCHEMA, SCHEMA, "HTTP");
        confirmable.setHandlerHttpSpecJson("{\"method\":\"GET\",\"urlTemplate\":\"/api/ai/toggle\","
                + "\"headerTemplates\":{},\"authForwarding\":\"PRINCIPAL_TOKEN\"}");
        confirmable.setPolicyJson("{\"headless\":true,\"confirmable\":true,\"confirmPrompt\":\"¿Seguro?\"}");
        ToolEntity read = new ToolEntity("default", "legacy_read", 1, SCHEMA, SCHEMA, "HTTP");
        read.setHandlerHttpSpecJson("{\"method\":\"GET\",\"urlTemplate\":\"/api/ai/sales\","
                + "\"headerTemplates\":{},\"authForwarding\":\"PRINCIPAL_TOKEN\"}");
        read.setPolicyJson("{\"headless\":true,\"timeout\":\"PT30S\"}");

        ToolDescriptor asPost = mapper.toDescriptor(post);
        ToolDescriptor asConfirmable = mapper.toDescriptor(confirmable);

        assertThat(asPost.policy().headless()).isFalse();
        assertThat(asConfirmable.policy().headless()).isFalse();
        // Everything else of the row is kept: the tool still works in a conversation.
        assertThat(asConfirmable.policy().confirmable()).isTrue();
        assertThat(asConfirmable.policy().confirmPrompt()).isEqualTo("¿Seguro?");
        assertThat(mapper.toDescriptor(read).policy().headless()).isTrue();
        assertThat(mapper.toDescriptor(read).policy().timeout()).hasSeconds(30);
    }

    private static void refused(WebTestClient.ResponseSpec response) {
        response.expectStatus().isBadRequest().expectBody()
                .jsonPath("$.error").isEqualTo("headless_not_allowed")
                .jsonPath("$.message").isNotEmpty();
    }

    private static AdminDtos.ToolHandlerDto http(String method) {
        return new AdminDtos.ToolHandlerDto("HTTP", null, new AdminDtos.HttpHandlerDto(
                method, "/api/ai/reports/sales", Map.of("X-Agent-Channel", "{session.channel}"),
                null, null, "PRINCIPAL_TOKEN"));
    }

    private WebTestClient.ResponseSpec tool(String tenant, String id, AdminDtos.ToolHandlerDto handler,
                                            Map<String, Object> policy) {
        return post("/v1/admin/tools", new AdminDtos.ToolRequest(tenant, id, SCHEMA, SCHEMA, Map.of(), handler,
                policy, null, null, null));
    }

    private ToolEntity latest(String tenant, String id) {
        return tools.findByTenantIdAndIdOrderByVersionDesc(tenant, id).get(0);
    }

    private void tenant(String id) {
        post("/v1/admin/tenants", new AdminDtos.TenantRequest(
                id, id, "https://" + id + ".example", List.of(), List.of(), true, null, null, Map.of()))
                .expectStatus().isCreated();
    }

    private WebTestClient.ResponseSpec post(String uri, Object body) {
        return client.post().uri(uri).contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }
}
