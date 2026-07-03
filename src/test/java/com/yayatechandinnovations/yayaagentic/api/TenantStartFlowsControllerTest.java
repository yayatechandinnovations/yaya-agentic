package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.AdminDtos;
import com.yayatechandinnovations.yayaagentic.api.dto.StartFlowDtos;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.operator_auth.TestAuthDance;
import com.yayatechandinnovations.yayaagentic.operator_auth.ratelimit.LoginRateLimiter;
import com.yayatechandinnovations.yayaagentic.startflow.Branch;
import com.yayatechandinnovations.yayaagentic.startflow.BranchTarget;
import com.yayatechandinnovations.yayaagentic.startflow.Condition;
import com.yayatechandinnovations.yayaagentic.startflow.FlowPolicy;
import com.yayatechandinnovations.yayaagentic.startflow.GreetingPolicy;
import com.yayatechandinnovations.yayaagentic.startflow.StartStep;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

/** Web-level CRUD + publish + validation for the Session Start Flow admin API. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
class TenantStartFlowsControllerTest {

    @Autowired WebTestClient client;
    @Autowired LoginRateLimiter rateLimiter;

    private static final String TENANT = "flows-test";

    @BeforeEach
    void auth() {
        rateLimiter.resetAll();
        client = TestAuthDance.asBootstrap(client);
        var req = new AdminDtos.TenantRequest(TENANT, "Flows Test", "https://flows-test.example",
                List.of(), List.of(), true, null, null, Map.of());
        client.post().uri("/v1/admin/tenants")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(req)
                .exchange().expectStatus().value(s -> assertThat(s).isIn(201, 409));
    }

    private StartFlowDtos.CreateRequest createReq(String id, boolean publish) {
        StartStep step = new StartStep.RunTool("s1", new Ids.ToolId("demo-lookup"),
                List.of(), List.of(),
                List.of(new Branch(Condition.always(), new BranchTarget.End("success"))), null);
        return new StartFlowDtos.CreateRequest(id, "Welcome", "a demo flow",
                List.of(step), GreetingPolicy.staticFallback("Hi there!"), FlowPolicy.defaults(), publish);
    }

    @Test
    void create_list_get_publish_roundtrip() {
        // Create (DRAFT).
        StartFlowDtos.FlowResponse created = client.post()
                .uri("/v1/admin/tenants/{t}/start-flows", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(createReq("welcome", false))
                .exchange().expectStatus().isCreated()
                .expectBody(StartFlowDtos.FlowResponse.class).returnResult().getResponseBody();
        assertThat(created).isNotNull();
        assertThat(created.id()).isEqualTo("welcome");
        assertThat(created.version()).isEqualTo(1);
        assertThat(created.status()).isEqualTo("DRAFT");
        assertThat(created.steps()).hasSize(1);

        // List.
        client.get().uri("/v1/admin/tenants/{t}/start-flows", TENANT)
                .exchange().expectStatus().isOk()
                .expectBodyList(StartFlowDtos.FlowResponse.class)
                .value(list -> assertThat(list).extracting(StartFlowDtos.FlowResponse::id).contains("welcome"));

        // Get.
        client.get().uri("/v1/admin/tenants/{t}/start-flows/{id}", TENANT, "welcome")
                .exchange().expectStatus().isOk()
                .expectBody(StartFlowDtos.FlowResponse.class)
                .value(f -> assertThat(f.name()).isEqualTo("Welcome"));

        // Publish → ACTIVE.
        client.post().uri("/v1/admin/tenants/{t}/start-flows/{id}/publish", TENANT, "welcome")
                .exchange().expectStatus().isOk()
                .expectBody(StartFlowDtos.FlowResponse.class)
                .value(f -> assertThat(f.status()).isEqualTo("ACTIVE"));
    }

    @Test
    void update_publishes_a_new_version() {
        client.post().uri("/v1/admin/tenants/{t}/start-flows", TENANT)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(createReq("versioned", true))
                .exchange().expectStatus().isCreated();

        StartStep step = new StartStep.RunTool("s1", new Ids.ToolId("demo-lookup"),
                List.of(), List.of(),
                List.of(new Branch(Condition.always(), new BranchTarget.End("success"))), null);
        var update = new StartFlowDtos.UpdateRequest("Welcome v2", null, List.of(step), null, null);

        client.put().uri("/v1/admin/tenants/{t}/start-flows/{id}", TENANT, "versioned")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(update)
                .exchange().expectStatus().isOk()
                .expectBody(StartFlowDtos.FlowResponse.class)
                .value(f -> {
                    assertThat(f.version()).isEqualTo(2);
                    assertThat(f.status()).isEqualTo("DRAFT");
                    assertThat(f.name()).isEqualTo("Welcome v2");
                });
    }

    @Test
    void rejects_a_flow_whose_branch_targets_an_unknown_step() {
        StartStep bad = new StartStep.RunTool("s1", new Ids.ToolId("demo-lookup"),
                List.of(), List.of(),
                List.of(new Branch(Condition.always(), new BranchTarget.Step("ghost"))), null);
        var req = new StartFlowDtos.CreateRequest("broken", "Broken", null,
                List.of(bad), GreetingPolicy.staticFallback("Hi"), FlowPolicy.defaults(), false);

        client.post().uri("/v1/admin/tenants/{t}/start-flows", TENANT)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(req)
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    void get_unknown_flow_is_404() {
        client.get().uri("/v1/admin/tenants/{t}/start-flows/{id}", TENANT, "nope")
                .exchange().expectStatus().isNotFound();
    }
}
