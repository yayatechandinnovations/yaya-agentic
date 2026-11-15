package com.yayatechandinnovations.yayaagentic.headless;

import com.yayatechandinnovations.yayaagentic.llm.LlmClient;
import com.yayatechandinnovations.yayaagentic.testsupport.ScriptedLlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The conversation of a headless run belongs to no screen
 * ({@code docs/guides/admin-panel-sessions.md} §6, §17): the same person's
 * panel is never handed it, cannot read it back and cannot continue it.
 */
class HeadlessSessionHiddenTest extends HeadlessRunTestBase {

    @Test
    void latest_keeps_returning_the_persons_own_session_after_a_run() {
        String subject = "u-" + UUID.randomUUID();
        String staff = staffToken(subject, Map.of());
        String own = startInteractive(staff);
        llm.say("Hola.");
        sendInteractive(own, staff, "hola");

        llm.say("Ayer se vendieron RD$1,250.00.");
        String runId = accepted(body(runToken(subject), "¿Cuánto se vendió ayer?"));
        assertThat(finished(runId)).containsEntry("status", "completed");
        String hidden = sessionOf(runId);

        // The run's conversation is the newer one of the two, and is not the one offered.
        assertThat(hidden).isNotEqualTo(own);
        assertThat(jdbc.queryForObject("SELECT principal_subject FROM sessions WHERE id = ?::uuid",
                String.class, hidden)).isEqualTo(subject);
        Map<String, Object> latest = latest(staff, true);
        assertThat(latest).containsEntry("state", "RESUMABLE").containsEntry("sessionId", own);
        assertThat(String.valueOf(latest.get("turns"))).doesNotContain("1,250");
        assertThat(latest(staff, false)).containsEntry("sessionId", own);
    }

    @Test
    void a_person_with_no_session_of_their_own_is_offered_none_even_while_a_run_is_in_flight() {
        String subject = "u-" + UUID.randomUUID();
        String staff = staffToken(subject, Map.of());
        Sinks.Many<LlmClient.LlmEvent> model = Sinks.many().unicast().onBackpressureBuffer();
        llm.round(model.asFlux());

        String runId = accepted(body(runToken(subject), "¿Cuánto se vendió ayer?"));
        await(() -> llm.requests().size() == 1);
        String hidden = sessionOf(runId);
        try {
            // Open, the person's, the newest — and still not theirs to reopen.
            assertThat(jdbc.queryForObject("SELECT ended_at IS NULL FROM sessions WHERE id = ?::uuid",
                    Boolean.class, hidden)).isTrue();
            assertThat(latest(staff, true)).containsEntry("state", "NONE");
            assertNotFound(client.get().uri("/v1/sessions/" + hidden + "/history"), staff);
            assertNotFound(client.post().uri("/v1/sessions/" + hidden + "/messages")
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("text", "sigue")), staff);
            assertNotFound(client.post().uri("/v1/sessions/" + hidden + "/actions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("tool", "hl_me", "args", Map.of())), staff);
            assertNotFound(client.post().uri("/v1/sessions/" + hidden + "/end"), staff);
            assertNotFound(client.get().uri("/v1/sessions/" + hidden + "/inspector"), staff);
        } finally {
            model.tryEmitNext(new LlmClient.LlmEvent.TokenChunk("Ayer se vendieron RD$1,250.00."));
            model.tryEmitNext(new LlmClient.LlmEvent.Done(LlmClient.StopReason.END_TURN,
                    ScriptedLlmClient.ROUND_USAGE));
            model.tryEmitComplete();
        }
        // None of that touched the run.
        assertThat(finished(runId)).containsEntry("status", "completed")
                .containsEntry("answer", "Ayer se vendieron RD$1,250.00.");
        assertThat(llm.requests()).hasSize(1);
    }

    @Test
    void history_and_a_new_message_are_refused_after_the_run_too() {
        String subject = "u-" + UUID.randomUUID();
        String staff = staffToken(subject, Map.of());
        llm.say("Ayer se vendieron RD$1,250.00.");
        String hidden = sessionOf(finishedId(body(runToken(subject), "¿Cuánto se vendió ayer?")));

        assertNotFound(client.get().uri("/v1/sessions/" + hidden + "/history"), staff);
        assertNotFound(client.post().uri("/v1/sessions/" + hidden + "/messages")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("text", "¿y hoy?")), staff);
        assertThat(latest(staff, true)).containsEntry("state", "NONE");
        assertThat(llm.requests()).hasSize(1);

        // It is recorded all the same, for the operator: the question, the answer, its channel.
        assertThat(jdbc.queryForObject("SELECT channel FROM sessions WHERE id = ?::uuid", String.class, hidden))
                .isEqualTo("automation");
        List<String> roles = jdbc.queryForList(
                "SELECT role FROM turns WHERE session_id = ?::uuid ORDER BY idx", String.class, hidden);
        assertThat(roles).containsExactly("USER", "ASSISTANT");
    }

    private String finishedId(Map<String, Object> body) {
        String runId = accepted(body);
        finished(runId);
        return runId;
    }

    private Map<String, Object> latest(String staffToken, boolean withProfile) {
        String uri = "/v1/sessions/latest?tenant=" + tenant + (withProfile ? "&profileId=" + PROFILE : "");
        return read(client.get().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + staffToken)
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
    }

    private static void assertNotFound(org.springframework.test.web.reactive.server.WebTestClient.RequestHeadersSpec<?> request,
                                       String staffToken) {
        request.header(HttpHeaders.AUTHORIZATION, "Bearer " + staffToken)
                .exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("session_not_found");
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("condition not met in 10 s");
            sleep(25);
        }
    }
}
