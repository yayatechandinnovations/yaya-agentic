package com.yayatechandinnovations.yayaagentic.headless;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nothing in a headless run waits for a person
 * ({@code docs/guides/admin-panel-sessions.md} §17): what would frame a
 * confirmation, a code prompt, a verification or a question in a conversation
 * ends the run as {@code needs_interaction}, and leaves no pending state.
 */
class HeadlessNeverPausesTest extends HeadlessRunTestBase {

    private static final String[] PENDING = {
            "pending_confirm", "pending_plan", "pending_secret", "pending_elicit", "pending_entity_conflict",
            "pending_action_arg"};

    @Test
    void a_confirm_request_in_a_tool_result_ends_the_run() {
        llm.tool("c1", "hl_probe", Map.of("how", "confirm"));

        String runId = accepted("¿Cómo va la 12?");
        Map<String, Object> run = finished(runId);

        assertNeedsInteraction(run, runId);
        // The tool it asked a confirmation for was not run either.
        backend.verify(0, anyRequestedFor(urlPathEqualTo("/api/ai/toggle")));
    }

    @Test
    void a_secret_prompt_in_a_tool_result_ends_the_run() {
        llm.tool("c1", "hl_probe", Map.of("how", "code"));

        String runId = accepted("¿Cómo va la 12?");

        assertNeedsInteraction(finished(runId), runId);
    }

    @Test
    void a_step_up_offer_ends_the_run_and_mounts_no_verification() {
        Integer before = jdbc.queryForObject("SELECT COUNT(*) FROM step_up_audit WHERE tenant_id = ?",
                Integer.class, tenant);
        llm.tool("c1", "hl_stepup", Map.of());

        String runId = accepted("¿Cómo va la 12?");

        assertNeedsInteraction(finished(runId), runId);
        assertThat(probe.inputs).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM step_up_audit WHERE tenant_id = ?",
                Integer.class, tenant)).isEqualTo(before);
    }

    @Test
    void a_missing_argument_is_not_asked_for() {
        // hl_needs_text requires «text»: in a conversation the engine would ask for it.
        llm.tool("c1", "hl_needs_text", Map.of());

        String runId = accepted("¿Cómo va la 12?");

        assertNeedsInteraction(finished(runId), runId);
        assertThat(probe.inputs).isEmpty();
    }

    @Test
    void a_ui_hint_in_a_tool_result_is_dropped_and_the_run_goes_on() {
        llm.tool("c1", "hl_probe", Map.of("how", "hint"));
        llm.say("Todo en orden.");

        String runId = accepted("¿Cómo va la 12?");
        Map<String, Object> run = finished(runId);

        assertThat(run).containsEntry("status", "completed").containsEntry("answer", "Todo en orden.");
        // Neither the model nor the stored turns got the frame.
        assertThat(resultsGiven(llm.lastRequest()).get("c1")).isEqualTo(Map.of("ok", true));
        String turns = jdbc.queryForObject(
                "SELECT COALESCE(string_agg(row_to_json(t)::text, ' '), '') FROM turns t WHERE session_id = ?::uuid",
                String.class, sessionOf(runId));
        assertThat(turns).contains("hl_probe").doesNotContain("draft-of-the-hint").doesNotContain("_uiHint");
        assertThat(memory.written(sessionOf(runId))).doesNotContainKeys(PENDING);
    }

    private void assertNeedsInteraction(Map<String, Object> run, String runId) {
        assertThat(run).containsEntry("status", "failed").doesNotContainKey("answer");
        assertThat(failureCode(run)).isEqualTo("needs_interaction");
        // The run stopped there: the model was not asked again.
        assertThat(llm.requests()).hasSize(1);
        assertThat(memory.written(sessionOf(runId))).doesNotContainKeys(PENDING);
        // The conversation is closed.
        assertThat(jdbc.queryForObject("SELECT ended_at IS NOT NULL FROM sessions WHERE id = ?::uuid",
                Boolean.class, sessionOf(runId))).isTrue();
    }
}
