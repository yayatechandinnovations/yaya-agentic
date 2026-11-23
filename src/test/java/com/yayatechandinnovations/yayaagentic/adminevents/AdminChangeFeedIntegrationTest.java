package com.yayatechandinnovations.yayaagentic.adminevents;

import com.yayatechandinnovations.yayaagentic.api.dto.AdminDtos;
import com.yayatechandinnovations.yayaagentic.operator_auth.TestAuthDance;
import com.yayatechandinnovations.yayaagentic.operator_auth.ratelimit.LoginRateLimiter;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * The console-facing contract end to end: a committed admin save shows up on
 * {@code /v1/admin/events}; validation failures name their field; a stale
 * {@code baseVersion} is a 409 carrying the version that won.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
class AdminChangeFeedIntegrationTest {

    private static final String TENANT = "events-test";

    @Autowired WebTestClient anonymous;
    WebTestClient client;
    @Autowired LoginRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter.resetAll();
        client = TestAuthDance.asBootstrap(anonymous);
        client.post().uri("/v1/admin/tenants").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new AdminDtos.TenantRequest(TENANT, "Events Test", "https://events-test.example",
                        List.of(), List.of(), true, null, null, Map.of()))
                .exchange().expectStatus().value(s -> assertThat(s).isIn(201, 409));
    }

    private AdminDtos.ToolRequest tool(String id) {
        return new AdminDtos.ToolRequest(TENANT, id, "{\"type\":\"object\"}", "{\"type\":\"object\"}",
                Map.of(), new AdminDtos.ToolHandlerDto("BEAN", "echoTool", null), Map.of());
    }

    @Test
    void a_committed_tool_save_is_announced_on_the_tenants_feed() {
        var events = client.mutate().responseTimeout(Duration.ofSeconds(20)).build()
                .get().uri("/v1/admin/events?tenant=" + TENANT)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange().expectStatus().isOk()
                .returnResult(new ParameterizedTypeReference<ServerSentEvent<Map<String, Object>>>() {})
                .getResponseBody();

        var first = events.filter(e -> !"hello".equals(e.event())).next().toFuture();
        client.post().uri("/v1/admin/tools").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(tool("feed_probe")).exchange().expectStatus().isCreated();

        ServerSentEvent<Map<String, Object>> e;
        try {
            e = first.get(20, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new AssertionError("no change event arrived", ex);
        }
        assertThat(e.event()).isEqualTo("resource-changed");
        assertThat(e.id()).isNotBlank();
        assertThat(e.data()).containsEntry("tenant", TENANT).containsEntry("kind", "TOOL")
                .containsEntry("id", "feed_probe").containsEntry("op", "CREATED");
    }

    @Test
    void a_validation_failure_names_the_field_it_is_about() {
        var req = new AdminDtos.ToolRequest(TENANT, "feed_bad", "{\"type\":\"object\"}", "{\"type\":\"object\"}",
                Map.of(), new AdminDtos.ToolHandlerDto("BEAN", " ", null), Map.of());
        client.post().uri("/v1/admin/tools").contentType(MediaType.APPLICATION_JSON).bodyValue(req)
                .exchange().expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("BAD_REQUEST")
                .jsonPath("$.fieldErrors['handler.beanName']").value(m -> assertThat((String) m).contains("required"));
    }

    @Test
    void saving_on_top_of_a_stale_version_is_a_conflict_naming_the_current_one() {
        client.post().uri("/v1/admin/tools").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(tool("feed_versioned")).exchange().expectStatus().isCreated();
        client.post().uri("/v1/admin/tools?baseVersion=1").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(tool("feed_versioned")).exchange().expectStatus().isCreated();

        client.post().uri("/v1/admin/tools?baseVersion=1").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(tool("feed_versioned")).exchange().expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.error").isEqualTo("version_conflict")
                .jsonPath("$.currentVersion").isEqualTo(2);
    }

    @Test
    void the_feed_needs_a_signed_in_operator() {
        var res = anonymous.get().uri("/v1/admin/events?tenant=" + TENANT).accept(MediaType.TEXT_EVENT_STREAM)
                .exchange().returnResult(String.class);
        assertThat(res.getStatus().value()).isEqualTo(401);
    }
}
