package com.yayatechandinnovations.yayaagentic.headless;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yayatechandinnovations.yayaagentic.api.InspectorService;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.startflow.FlowPolicy;
import com.yayatechandinnovations.yayaagentic.startflow.GreetingPolicy;
import com.yayatechandinnovations.yayaagentic.startflow.StartFlow;
import com.yayatechandinnovations.yayaagentic.startflow.StartFlowRegistry;
import com.yayatechandinnovations.yayaagentic.startflow.StartStep;
import com.yayatechandinnovations.yayaagentic.startflow.StepErrorPolicy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A run from start to end ({@code docs/guides/admin-panel-sessions.md} §17):
 * how it begins, each way it can fail, what a restart does to it, how long its
 * answer is kept, and where its token is — nowhere.
 */
class HeadlessRunLifecycleTest extends HeadlessRunTestBase {

    @Autowired StartFlowRegistry startFlows;
    @Autowired InspectorService inspector;

    /** The profile opens with a briefing, as the owner profile does (owner_briefing_start). */
    @BeforeAll
    void giveTheProfileAStartFlow() {
        Ids.StartFlowId flowId = new Ids.StartFlowId("hl_briefing_start_" + run, 1);
        startFlows.register(new StartFlow(flowId, new Ids.TenantId(tenant), "Briefing", "the opening briefing",
                List.of(new StartStep.RunTool("briefing", new Ids.ToolId("hl_briefing"), List.of(), List.of(),
                        List.of(), StepErrorPolicy.cont())),
                GreetingPolicy.staticFallback("Hola."), FlowPolicy.defaults(),
                StartFlow.FlowStatus.ACTIVE, Instant.now(), Instant.now()));
        registerProfile(tenant, 8, flowId);
    }

    @Test
    void no_start_flow_runs_for_a_headless_run() {
        // With a person present the profile's start flow calls its briefing tool…
        startInteractive(staffToken("u-" + UUID.randomUUID(), Map.of()));
        backend.verify(1, getRequestedFor(urlPathEqualTo("/api/ai/briefing")));
        backend.resetRequests();

        // …and a headless run goes straight to the question.
        llm.say("Ayer se vendieron RD$1,250.00.");
        Map<String, Object> run = runToEnd("¿Cuánto se vendió ayer?");

        assertThat(run).containsEntry("status", "completed");
        backend.verify(0, anyRequestedFor(urlPathEqualTo("/api/ai/briefing")));
        assertThat(llm.requests()).hasSize(1);
        assertThat(llm.lastRequest().history()).hasSize(1);
    }

    @Test
    void the_model_is_told_nobody_is_present_in_the_per_turn_part_only() {
        llm.say("Ayer se vendieron RD$1,250.00.");
        Map<String, Object> body = body(runToken("u-" + UUID.randomUUID()), "¿Cuánto se vendió ayer?");
        body.put("limits", Map.of("maxAnswerChars", 900, "deadlineSeconds", 60));
        finished(accepted(body));
        String suffix = llm.lastRequest().variableSuffix();
        String prefix = llm.lastRequest().cacheablePrefix();

        assertThat(suffix).contains("Esta es una ejecución automática: nadie está presente.")
                .contains("en 900 caracteres como máximo")
                .contains("(America/Santo_Domingo)")
                .contains("Empieza por assistant_me.");
        assertThat(prefix).doesNotContain("ejecución automática");

        // The cached prefix is the profile's own: byte for byte what a conversation gets.
        String token = staffToken("u-" + UUID.randomUUID(), Map.of());
        String sid = startInteractive(token);
        llm.say("Hola.");
        sendInteractive(sid, token, "hola");
        assertThat(llm.lastRequest().cacheablePrefix()).isEqualTo(prefix);
        assertThat(llm.lastRequest().variableSuffix()).doesNotContain("ejecución automática");
    }

    @Test
    void an_answer_longer_than_asked_is_cut() {
        llm.say("Ventas. " + "a".repeat(400));
        Map<String, Object> body = body(runToken("u-" + UUID.randomUUID()), "¿Cuánto se vendió ayer?");
        body.put("limits", Map.of("maxAnswerChars", 200, "deadlineSeconds", 60));

        Map<String, Object> run = finished(accepted(body));

        assertThat(run).containsEntry("status", "completed");
        assertThat((String) run.get("answer")).hasSize(200).startsWith("Ventas. aaa").endsWith("a…");
    }

    @Test
    void no_text_or_only_a_question_is_no_answer() {
        llm.say("   ");
        Map<String, Object> silent = runToEnd("¿Cuánto se vendió ayer?");
        assertThat(silent).containsEntry("status", "failed").doesNotContainKey("answer");
        assertThat(failureCode(silent)).isEqualTo("no_answer");

        llm.say("¿De qué fecha quieres el resumen? ¿De ayer o de hoy?");
        Map<String, Object> asking = runToEnd("¿Cuánto se vendió?");
        assertThat(failureCode(asking)).isEqualTo("no_answer");

        // A question at the end of a real answer is still an answer.
        llm.say("Ayer se vendieron RD$1,250.00. ¿Algo más?");
        assertThat(runToEnd("¿Cuánto se vendió ayer?")).containsEntry("status", "completed");
    }

    @Test
    void working_text_before_a_tool_call_is_not_part_of_the_answer() {
        llm.round(Flux.just(new com.yayatechandinnovations.yayaagentic.llm.LlmClient.LlmEvent.TokenChunk(
                        "Voy a revisar las ventas."),
                call("c1", "hl_sales", Map.of("what", "ayer")),
                new com.yayatechandinnovations.yayaagentic.llm.LlmClient.LlmEvent.Done(
                        com.yayatechandinnovations.yayaagentic.llm.LlmClient.StopReason.TOOL_USE,
                        com.yayatechandinnovations.yayaagentic.testsupport.ScriptedLlmClient.ROUND_USAGE)));
        llm.say("Ayer se vendieron RD$1,250.00.");

        assertThat(runToEnd("¿Cuánto se vendió ayer?")).containsEntry("answer", "Ayer se vendieron RD$1,250.00.");
    }

    @Test
    void the_tool_round_ceiling_ends_the_run() {
        // The profile allows 8 rounds; the model never stops calling.
        llm.alwaysCall("hl_sales");

        Map<String, Object> run = runToEnd("¿Cuánto se vendió ayer?");

        assertThat(run).containsEntry("status", "failed").doesNotContainKey("answer");
        assertThat(failureCode(run)).isEqualTo("tool_rounds_exhausted");
        assertThat(llm.requests()).hasSize(8);
        assertThat(cast(run.get("usage"))).containsEntry("rounds", 8).containsEntry("toolCalls", 8);
        assertThat(run.get("tools")).isEqualTo(List.of("hl_sales"));
    }

    @Test
    void a_401_from_the_backend_ends_the_run_as_principal_rejected() {
        llm.tool("c1", "hl_expired", Map.of());
        llm.say("No debería llegar aquí.");

        Map<String, Object> run = runToEnd("¿Cuánto se vendió ayer?");

        assertThat(failureCode(run)).isEqualTo("principal_rejected");
        assertThat(llm.requests()).hasSize(1);
    }

    @Test
    void a_model_failure_is_llm_error() {
        llm.failNextRound();

        Map<String, Object> run = runToEnd("¿Cuánto se vendió ayer?");

        assertThat(failureCode(run)).isEqualTo("llm_error");
        assertThat(String.valueOf(run.get("failure"))).doesNotContain("scripted model failure");
    }

    @Test
    void a_run_cut_while_the_platform_stops_is_interrupted_not_a_model_failure() {
        // 4H: an orderly stop (a deploy) cuts the model call of a run in flight. Seen running: the
        // run ended llm_error, which the install does not hand over again — the summary was lost.
        service.stopping(true);
        try {
            llm.failNextRound();

            Map<String, Object> run = runToEnd("¿Cuánto se vendió ayer?");

            assertThat(run).containsEntry("status", "failed").doesNotContainKey("answer");
            assertThat(failureCode(run)).isEqualTo("interrupted");
        } finally {
            service.stopping(false);
        }
        // Outside a stop a model failure is still the run's own.
        llm.failNextRound();
        assertThat(failureCode(runToEnd("¿Cuánto se vendió ayer?"))).isEqualTo("llm_error");
    }

    @Test
    void a_run_past_its_deadline_is_stopped() {
        llm.round(Flux.never());
        Map<String, Object> body = body(runToken("u-" + UUID.randomUUID()), "¿Cuánto se vendió ayer?");
        body.put("limits", Map.of("maxAnswerChars", 1800, "deadlineSeconds", 30));

        Map<String, Object> run = finished(accepted(body), 45_000);

        assertThat(failureCode(run)).isEqualTo("deadline_exceeded");
        // The platform's own clock, start to end: what the test thread measured around its polls
        // also counts every pause of the machine, which is not the deadline's doing.
        long took = java.time.Duration.between(Instant.parse((String) run.get("startedAt")),
                Instant.parse((String) run.get("finishedAt"))).toMillis();
        assertThat(took).isBetween(29_000L, 40_000L);
        // Its place is free again.
        llm.say("Listo.");
        assertThat(runToEnd("¿Cuánto se vendió ayer?")).containsEntry("status", "completed");
    }

    @Test
    void a_run_the_process_did_not_finish_is_interrupted_at_the_next_start() {
        // What a stopped process leaves behind: one run waiting, one running.
        String waiting = "hr_TESTWAIT" + run.toUpperCase();
        String running = "hr_TESTRUNS" + run.toUpperCase();
        store.insertQueued(waiting, tenant, PROFILE, 1, "k-wait-" + run, "u-1", "¿Cuánto se vendió?", null, Instant.now());
        store.insertQueued(running, tenant, PROFILE, 1, "k-run-" + run, "u-1", "¿Cuánto se vendió?", null, Instant.now());
        store.markRunning(tenant, running, Instant.now());
        assertThat(get(waiting)).containsEntry("status", "queued").doesNotContainKeys("answer", "failure");
        assertThat(get(running)).containsEntry("status", "running");

        assertThat(service.failUnfinished()).isEqualTo(2);

        for (String id : List.of(waiting, running)) {
            Map<String, Object> run = get(id);
            assertThat(run).containsEntry("status", "failed").containsKey("finishedAt");
            assertThat(failureCode(run)).isEqualTo("interrupted");
        }
        // A finished run is left as it was.
        llm.say("Listo.");
        String done = accepted("¿Cuánto se vendió ayer?");
        finished(done);
        assertThat(service.failUnfinished()).isZero();
        assertThat(get(done)).containsEntry("status", "completed");
    }

    @Test
    void a_run_left_unfinished_past_any_deadline_is_closed_by_housekeeping_and_frees_the_tenant() {
        // What a worker that could not write its outcome leaves behind: two rows still
        // running long after the longest deadline (300 s) — and one queued for over an hour.
        String stuckA = "hr_TESTSTKA" + run.toUpperCase();
        String stuckB = "hr_TESTSTKB" + run.toUpperCase();
        String stuckQueued = "hr_TESTSTKQ" + run.toUpperCase();
        // …and two that are within their time: started 4 minutes ago, queued 11 minutes ago.
        String working = "hr_TESTWORK" + run.toUpperCase();
        String inLine = "hr_TESTLINE" + run.toUpperCase();
        try {
            for (String id : List.of(stuckA, stuckB, working)) {
                store.insertQueued(id, tenant, PROFILE, 1, "k-" + id, "u-1", "¿Cuánto se vendió?", null, Instant.now());
                store.markRunning(tenant, id, Instant.now());
            }
            for (String id : List.of(stuckQueued, inLine)) {
                store.insertQueued(id, tenant, PROFILE, 1, "k-" + id, "u-1", "¿Cuánto se vendió?", null, Instant.now());
            }
            started(stuckA, "11 minutes");
            started(stuckB, "3 hours");
            started(working, "4 minutes");
            age(stuckQueued, "61 minutes");
            age(inLine, "11 minutes");

            service.housekeep();

            for (String id : List.of(stuckA, stuckB, stuckQueued)) {
                Map<String, Object> closed = get(id);
                assertThat(closed).as(id).containsEntry("status", "failed").containsKey("finishedAt");
                assertThat(failureCode(closed)).as(id).isEqualTo("interrupted");
            }
            assertThat(get(working)).containsEntry("status", "running");
            assertThat(get(inLine)).containsEntry("status", "queued");

            // A worker that ends at that moment does not write over the closed run…
            assertThat(store.complete(tenant, stuckA, "tarde", null, null, Instant.now())).isFalse();
            assertThat(store.fail(tenant, stuckB, "llm_error", "tarde", null, null, Instant.now())).isFalse();
            assertThat(store.markRunning(tenant, stuckQueued, Instant.now())).isFalse();
            assertThat(failureCode(get(stuckA))).isEqualTo("interrupted");
            assertThat(failureCode(get(stuckB))).isEqualTo("interrupted");
            // …and one that is within its time still finishes as itself.
            assertThat(store.complete(tenant, working, "Listo.", null, null, Instant.now())).isTrue();
            assertThat(get(working)).containsEntry("status", "completed").containsEntry("answer", "Listo.");
            store.fail(tenant, inLine, "interrupted", "test", null, null, Instant.now());

            // The tenant's two places are free again: with the stuck rows it was 429 busy.
            llm.say("Listo.");
            assertThat(runToEnd("¿Cuánto se vendió ayer?")).containsEntry("status", "completed");
        } finally {
            // Whatever the outcome, nothing of this test stays in flight for the next one.
            for (String id : List.of(stuckA, stuckB, stuckQueued, working, inLine)) {
                store.fail(tenant, id, "interrupted", "test", null, null, Instant.now());
            }
        }
    }

    @Test
    void the_answer_is_erased_after_24_hours_and_the_run_after_30_days() {
        llm.say("Ayer se vendieron RD$1,250.00.");
        String runId = accepted("¿Cuánto se vendió ayer?");
        assertThat(finished(runId)).containsEntry("answer", "Ayer se vendieron RD$1,250.00.");

        // 23 hours: still there.
        age(runId, "23 hours");
        service.housekeep();
        assertThat(get(runId)).containsEntry("answer", "Ayer se vendieron RD$1,250.00.");

        // 25 hours: the run is still told, its text is gone.
        age(runId, "25 hours");
        service.housekeep();
        assertThat(get(runId)).containsEntry("status", "completed").doesNotContainKey("answer")
                .containsKeys("usage", "tools", "finishedAt");
        assertThat(jdbc.queryForObject("SELECT answer FROM headless_runs WHERE id = ?", String.class, runId)).isNull();

        // 31 days: no such run.
        age(runId, "31 days");
        service.housekeep();
        poll(serviceToken(), runId).expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("run_not_found");
    }

    @Test
    void the_run_token_is_in_no_table_no_log_and_not_in_the_inspector() {
        String runToken = runToken("u-" + UUID.randomUUID());
        String signature = runToken.substring(runToken.lastIndexOf('.') + 1);
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
        String runId;
        String inspected;
        try {
            // A call that works, one the backend refuses, one that is not available, then the answer.
            llm.round(answer(call("c1", "hl_me", Map.of()), call("c2", "memory_save", Map.of("text", "x"))));
            llm.say("Eres la dueña.");
            runId = accepted(body(runToken, "¿Quién soy?"));
            assertThat(finished(runId)).containsEntry("status", "completed");
            inspected = write(inspector.snapshot(new Ids.SessionId(sessionOf(runId))));
        } finally {
            root.detachAppender(logs);
        }
        String session = sessionOf(runId);

        // The backend did get it — and nothing else kept it.
        assertThat(backend.findAll(getRequestedFor(urlPathEqualTo("/api/ai/me"))).get(0).getHeader("Authorization"))
                .isEqualTo("Bearer " + runToken);
        assertThat(logs.list).isNotEmpty();
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(line -> line.contains(signature) || line.contains(runToken));
        for (String sql : List.of(
                "SELECT row_to_json(r)::text FROM headless_runs r WHERE id = '" + runId + "'",
                "SELECT row_to_json(s)::text FROM sessions s WHERE id = '" + session + "'::uuid",
                "SELECT COALESCE(string_agg(row_to_json(t)::text, ' '), '') FROM turns t WHERE session_id = '"
                        + session + "'::uuid",
                "SELECT COALESCE(string_agg(row_to_json(a)::text, ' '), '') FROM audit_authz a WHERE session_id = '"
                        + session + "'::uuid")) {
            assertThat(jdbc.queryForObject(sql, String.class)).as(sql).doesNotContain(signature);
        }
        assertThat(jdbc.queryForObject("SELECT auth_token_ciphertext FROM sessions WHERE id = ?::uuid",
                String.class, session)).isNull();
        assertThat(inspected).doesNotContain(signature);
        assertThat(String.valueOf(memory.written(session))).doesNotContain(signature);
        // The run's own view carries neither the token nor the question.
        assertThat(String.valueOf(get(runId))).doesNotContain(signature).doesNotContain("¿Quién soy?");
    }

    private void started(String runId, String interval) {
        jdbc.update("UPDATE headless_runs SET created_at = NOW() - INTERVAL '" + interval + "', started_at = NOW() - INTERVAL '"
                + interval + "' WHERE id = ?", runId);
    }

    private void age(String runId, String interval) {
        jdbc.update("UPDATE headless_runs SET created_at = NOW() - INTERVAL '" + interval + "' WHERE id = ?", runId);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object map) {
        return (Map<String, Object>) map;
    }
}
