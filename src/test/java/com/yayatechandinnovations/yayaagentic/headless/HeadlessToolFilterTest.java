package com.yayatechandinnovations.yayaagentic.headless;

import com.yayatechandinnovations.yayaagentic.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a headless run can reach ({@code docs/guides/admin-panel-sessions.md}
 * §17): the model is offered the tools flagged {@code policy.headless} and no
 * other, and a call to any other never leaves the platform.
 */
class HeadlessToolFilterTest extends HeadlessRunTestBase {

    private static final String[] FLAGGED = {
            "hl_me", "hl_sales", "hl_briefing", "hl_expired", "hl_probe", "hl_needs_text", "hl_stepup"};
    private static final String[] NOT_FLAGGED = {
            "memory_save", "report_export", "hl_customers", "hl_toggle", "hl_toggle_flagged"};

    @Test
    void only_flagged_tools_are_in_the_provider_request() {
        llm.tool("c1", "hl_me", Map.of());
        llm.tool("c2", "hl_sales", Map.of("what", "ayer"));
        llm.say("Ayer se vendieron RD$1,250.00.");

        Map<String, Object> run = runToEnd("¿Cuánto se vendió ayer?");

        assertThat(run).containsEntry("status", "completed").containsEntry("answer", "Ayer se vendieron RD$1,250.00.");
        assertThat(llm.requests()).hasSize(3);
        for (LlmClient.LlmRequest request : llm.requests()) {
            // memory_save and report_export are POSTs the mask's "read-only"
            // test (idempotent && !confirmable) would have let through.
            assertThat(toolNames(request)).containsExactlyInAnyOrder(FLAGGED).doesNotContain(NOT_FLAGGED);
        }
        assertThat(run.get("tools")).isEqualTo(List.of("hl_me", "hl_sales"));
        assertThat(run.get("usage")).isEqualTo(Map.of("inputTokens", 300, "outputTokens", 60,
                "cacheReadTokens", 0, "rounds", 3, "toolCalls", 2));
        backend.verify(1, getRequestedFor(urlPathEqualTo("/api/ai/me")));
        backend.verify(1, getRequestedFor(urlPathEqualTo("/api/ai/sales")));
    }

    @Test
    void a_call_to_a_tool_that_is_not_flagged_comes_back_tool_not_available_and_the_run_goes_on() {
        llm.round(answer(
                call("w1", "memory_save", Map.of("text", "anota esto")),
                call("w2", "report_export", Map.of()),
                call("w3", "hl_toggle", Map.of("station", "12")),
                call("w4", "hl_customers", Map.of()),
                call("w5", "hl_toggle_flagged", Map.of("station", "12")),
                call("w6", "no_such_tool", Map.of()),
                call("r1", "hl_sales", Map.of("what", "ayer"))));
        llm.say("Ayer se vendieron RD$1,250.00. No cambié nada.");

        String runId = accepted("Guarda una nota, exporta el reporte y para la venta en la 12.");
        Map<String, Object> run = finished(runId);

        // The run went on to its answer, and nothing but the read left the platform.
        assertThat(run).containsEntry("status", "completed")
                .containsEntry("answer", "Ayer se vendieron RD$1,250.00. No cambié nada.");
        for (String path : List.of("/api/ai/memory", "/api/ai/export", "/api/ai/toggle", "/api/ai/customers")) {
            backend.verify(0, anyRequestedFor(urlPathEqualTo(path)));
        }
        backend.verify(1, getRequestedFor(urlPathEqualTo("/api/ai/sales")));

        // Every refused call got its result, so the model could go on.
        Map<String, Object> results = resultsGiven(llm.lastRequest());
        assertThat(results).containsOnlyKeys("w1", "w2", "w3", "w4", "w5", "w6", "r1");
        for (String refused : List.of("w1", "w2", "w3", "w4", "w5", "w6")) {
            assertThat(results.get(refused)).as(refused)
                    .isEqualTo(Map.of("ok", false, "reason", "tool_not_available"));
        }
        assertThat(run.get("tools")).isEqualTo(List.of("hl_sales"));

        // No card was framed for the confirmable one, and nothing is left waiting.
        assertThat(memory.written(sessionOf(runId)))
                .doesNotContainKeys("pending_confirm", "pending_plan", "pending_secret", "pending_elicit");
    }

    @Test
    void an_ordinary_session_of_the_same_profile_is_still_offered_every_tool() {
        String token = staffToken("u-" + UUID.randomUUID(), Map.of());
        String sid = startInteractive(token);

        llm.say("Hola.");
        sendInteractive(sid, token, "hola");

        assertThat(toolNames(llm.lastRequest())).contains(FLAGGED).contains(NOT_FLAGGED);
    }
}
