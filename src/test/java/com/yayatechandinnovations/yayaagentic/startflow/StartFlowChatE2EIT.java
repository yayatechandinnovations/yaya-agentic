package com.yayatechandinnovations.yayaagentic.startflow;

import com.yayatechandinnovations.yayaagentic.api.dto.SessionDtos;
import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.engine.bootstrap.M0Catalog;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import com.yayatechandinnovations.yayaagentic.tool.BindingMode;
import com.yayatechandinnovations.yayaagentic.tool.ToolDescriptor;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandler;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandlerRef;
import com.yayatechandinnovations.yayaagentic.tool.ToolPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * End-to-end: a Session Start Flow bound via {@code channel.startFlowId} runs
 * through the real POST /v1/sessions path — resolver → registry → runner → the
 * same ToolExecutor + Authorizer as any tool call → a greeting shaped from the
 * tool result. Proves the "recognize returning customer" flagship over HTTP.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import({TestcontainersConfiguration.class, StartFlowChatE2EIT.DemoTools.class})
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
class StartFlowChatE2EIT {

    @Autowired WebTestClient client;
    @Autowired StartFlowRegistry startFlows;
    @Autowired M0Catalog catalog;

    private final Ids.TenantId tenant = new Ids.TenantId("default");

    @BeforeEach
    void seed() {
        catalog.registerTool(new ToolDescriptor(
                new Ids.ToolId("demo-lookup"), "{}", "{}",
                PermissionRequirement.none(),
                new ToolHandlerRef.Bean("demoLookupTool"), ToolPolicy.defaults()));
        catalog.registerTool(new ToolDescriptor(
                new Ids.ToolId("demo-verify"), "{}", "{}",
                PermissionRequirement.none(),
                new ToolHandlerRef.Bean("demoVerifyTool"), ToolPolicy.defaults()));
        startFlows.register(demoFlow());
        startFlows.register(demoLlmFlow());
        startFlows.register(demoOtpFlow());
    }

    @Test
    void bound_flow_shapes_the_greeting_from_the_tool_result() {
        SessionDtos.StartSessionResponse start = client.post().uri("/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SessionDtos.StartSessionRequest("default", "hello-world", 1, "web",
                        Map.of("channel.startFlowId", "demo-returning"), null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(SessionDtos.StartSessionResponse.class)
                .returnResult().getResponseBody();

        assertThat(start).isNotNull();
        assertThat(start.greeting()).isEqualTo("Welcome back Ana 👋");
        assertThat(start.quickReplies()).contains("Track my order");
    }

    @Test
    void without_the_binding_the_profile_greeting_is_unchanged() {
        SessionDtos.StartSessionResponse start = client.post().uri("/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SessionDtos.StartSessionRequest("default", "hello-world", 1, "web",
                        Map.of(), null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(SessionDtos.StartSessionResponse.class)
                .returnResult().getResponseBody();

        assertThat(start).isNotNull();
        assertThat(start.greeting()).doesNotContain("Welcome back");
    }

    @Test
    void llm_composed_opening_overrides_the_static_template() {
        SessionDtos.StartSessionResponse start = client.post().uri("/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SessionDtos.StartSessionRequest("default", "hello-world", 1, "web",
                        Map.of("channel.startFlowId", "demo-llm"), null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(SessionDtos.StartSessionResponse.class)
                .returnResult().getResponseBody();

        assertThat(start).isNotNull();
        // A composed opening was produced (via the stub LLM), so the greeting is
        // NOT the static fallback template — proving the engine took the LLM path.
        assertThat(start.greeting()).isNotBlank();
        assertThat(start.greeting()).isNotEqualTo("TEMPLATE_FALLBACK_SHOULD_NOT_APPEAR");
    }

    @Test
    void interactive_otp_flow_pauses_at_start_then_completes_on_reply() {
        // Turn 1 — start: the flow recognizes the customer, then pauses on the OTP
        // elicit, so the "greeting" IS the prompt.
        SessionDtos.StartSessionResponse start = client.post().uri("/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SessionDtos.StartSessionRequest("default", "hello-world", 1, "web",
                        Map.of("channel.startFlowId", "demo-otp"), null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(SessionDtos.StartSessionResponse.class)
                .returnResult().getResponseBody();

        assertThat(start).isNotNull();
        assertThat(start.greeting()).isEqualTo("Enter the code we texted you, Ana");

        // Turn 2 — the user's reply resumes the flow (not the LLM): verify runs and
        // the flow completes with the success greeting.
        String sse = sendAndCollect(start.sessionId(), "483920");
        assertThat(sse).contains("event:token");
        // sendAndCollect strips spaces; "You're verified, Ana!" → "You'reverified,Ana!"
        assertThat(sse).contains("You'reverified,Ana!");
        assertThat(sse).contains("event:end");
    }

    private String sendAndCollect(String sessionId, String text) {
        byte[] raw = client.post().uri("/v1/sessions/{id}/messages", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(new SessionDtos.MessageRequest(text))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .returnResult()
                .getResponseBody();
        return raw == null ? "" : new String(raw).replace(" ", "");
    }

    private StartFlow demoOtpFlow() {
        StartStep lookup = new StartStep.RunTool(
                "lookup", new Ids.ToolId("demo-lookup"),
                List.of(),
                List.of(new OutputCapture("/name", new CaptureTarget.Entity("customerName"))),
                List.of(), StepErrorPolicy.abort());
        StartStep otp = new StartStep.Elicit(
                "otp", "Enter the code we texted you, {customerName}",
                List.of(), "otp_entry", Map.of(),
                new CaptureTarget.StepVar("code"), List.of());
        StartStep verify = new StartStep.RunTool(
                "verify", new Ids.ToolId("demo-verify"),
                List.of(new ArgBinding("code", new ValueSource.Entity("code"), BindingMode.REQUIRED)),
                List.of(new OutputCapture("/verified", new CaptureTarget.Entity("phoneVerified"))),
                List.of(), StepErrorPolicy.abort());

        GreetingPolicy greeting = new GreetingPolicy(GreetingMode.STATIC_TEMPLATE,
                Map.of("success", "You're verified, {customerName}!"), "Hi there!", List.of(), null);

        return new StartFlow(new Ids.StartFlowId("demo-otp", 1), tenant,
                "Demo OTP", "recognize + verify",
                List.of(lookup, otp, verify), greeting, FlowPolicy.defaults(),
                StartFlow.FlowStatus.ACTIVE, Instant.now(), Instant.now());
    }

    private StartFlow demoLlmFlow() {
        StartStep lookup = new StartStep.RunTool(
                "step1", new Ids.ToolId("demo-lookup"),
                List.of(),
                List.of(new OutputCapture("/name", new CaptureTarget.Entity("customerName"))),
                List.of(), StepErrorPolicy.abort());

        GreetingPolicy greeting = new GreetingPolicy(GreetingMode.LLM_COMPOSED,
                Map.of("success", "TEMPLATE_FALLBACK_SHOULD_NOT_APPEAR"),
                "TEMPLATE_FALLBACK_SHOULD_NOT_APPEAR", List.of("Track my order"),
                new LlmOpening("Greet the returning customer by name and offer help.",
                        List.of("customerName"), List.of(), false, java.time.Duration.ofSeconds(6)));

        return new StartFlow(new Ids.StartFlowId("demo-llm", 1), tenant,
                "Demo LLM opening", "recognize + LLM greeting",
                List.of(lookup), greeting, FlowPolicy.defaults(),
                StartFlow.FlowStatus.ACTIVE, Instant.now(), Instant.now());
    }

    private StartFlow demoFlow() {
        StartStep lookup = new StartStep.RunTool(
                "step1", new Ids.ToolId("demo-lookup"),
                List.of(),
                List.of(new OutputCapture("/name", new CaptureTarget.Entity("customerName"))),
                List.of(new Branch(new Condition(new ValueSource.StepOutput("step1", "/found"),
                        Condition.Op.EQ, true), new BranchTarget.End("success"))),
                StepErrorPolicy.abort());

        GreetingPolicy greeting = new GreetingPolicy(GreetingMode.STATIC_TEMPLATE,
                Map.of("success", "Welcome back {customerName} 👋"),
                "Hi there!", List.of("Track my order"), null);

        return new StartFlow(new Ids.StartFlowId("demo-returning", 1), tenant,
                "Demo returning customer", "recognize by lookup",
                List.of(lookup), greeting, FlowPolicy.defaults(),
                StartFlow.FlowStatus.ACTIVE, Instant.now(), Instant.now());
    }

    @TestConfiguration
    static class DemoTools {
        /** Stands in for a real account lookup — returns a recognized customer. */
        @Bean
        ToolHandler<Object, Object> demoLookupTool() {
            return (Object input, ExecutionContext ctx) -> Map.of("found", true, "name", "Ana");
        }

        /** Stands in for OTP verification — always succeeds. */
        @Bean
        ToolHandler<Object, Object> demoVerifyTool() {
            return (Object input, ExecutionContext ctx) -> Map.of("verified", true);
        }
    }
}
