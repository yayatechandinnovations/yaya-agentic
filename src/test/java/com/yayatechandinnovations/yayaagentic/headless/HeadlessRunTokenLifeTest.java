package com.yayatechandinnovations.yayaagentic.headless;

import com.nimbusds.jwt.JWTClaimsSet;
import com.yayatechandinnovations.yayaagentic.llm.LlmClient;
import com.yayatechandinnovations.yayaagentic.testsupport.ScriptedLlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The run token must outlive the run ({@code docs/guides/admin-panel-sessions.md}
 * §17): the deadline counts from when a worker takes the run, the token from
 * when the caller minted it. A run whose token would end before its deadline
 * is refused at submit, or — when it was the wait that used the token up —
 * closed {@code token_expiring} without being started. One worker here, so a
 * run can be made to wait.
 */
@TestPropertySource(properties = "yaya.agentic.headless.workers=1")
class HeadlessRunTokenLifeTest extends HeadlessRunTestBase {

    /** A run token of a fresh person that ends {@code seconds} from now. */
    private String runTokenLiving(long seconds) {
        Date now = new Date();
        return sign(STAFF_SECRET, new JWTClaimsSet.Builder().issuer(staffIss).subject("u-" + UUID.randomUUID())
                .claim("org", business).claim("username", "user").issueTime(now)
                .expirationTime(new Date(now.getTime() + seconds * 1000)).jwtID(UUID.randomUUID().toString())
                .claim("via", "automation").claim("viaRef", "auto-1").claim("agentOnly", true)
                .claim("viaReadOnly", true).build());
    }

    private Map<String, Object> body(String token, int deadlineSeconds, String notAfter) {
        Map<String, Object> body = body(token, "¿Cuánto se vendió ayer?");
        body.put("limits", Map.of("maxAnswerChars", 1800, "deadlineSeconds", deadlineSeconds));
        if (notAfter != null) body.put("principalNotAfter", notAfter);
        return body;
    }

    @Test
    void a_token_that_ends_before_the_deadline_is_refused_at_submit_and_takes_no_message() {
        long before = usage.usage(tenant, null).messages();
        Long rows = jdbc.queryForObject("SELECT COUNT(*) FROM headless_runs WHERE tenant_id = ?", Long.class, tenant);

        // Said by the caller …
        submit(body(runToken("u-" + UUID.randomUUID()), 240, Instant.now().plusSeconds(200).toString()))
                .expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("token_expiring");
        // … or read from the token itself when the caller says nothing.
        submit(body(runTokenLiving(100), 240, null))
                .expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("token_expiring");
        // The earlier of the two counts.
        submit(body(runTokenLiving(100), 240, Instant.now().plusSeconds(590).toString()))
                .expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("token_expiring");

        assertThat(usage.usage(tenant, null).messages()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM headless_runs WHERE tenant_id = ?", Long.class, tenant))
                .isEqualTo(rows);
        assertThat(llm.requests()).isEmpty();
    }

    @Test
    void a_notAfter_that_is_not_an_instant_is_an_invalid_request() {
        submit(body(runToken("u-" + UUID.randomUUID()), 240, "mañana"))
                .expectStatus().isEqualTo(422).expectBody().jsonPath("$.code").isEqualTo("invalid_request");
    }

    @Test
    void a_run_with_enough_token_life_is_not_affected() {
        llm.tool("c1", "hl_me", Map.of());
        llm.say("Ayer se vendieron RD$1,250.00.");

        Map<String, Object> run = finished(accepted(
                body(runToken("u-" + UUID.randomUUID()), 240, Instant.now().plusSeconds(600).toString())));

        assertThat(run).containsEntry("status", "completed").containsEntry("answer", "Ayer se vendieron RD$1,250.00.");
        // No notAfter and a ten-minute token: as before.
        llm.say("Listo.");
        assertThat(runToEnd("¿Cuánto se vendió ayer?")).containsEntry("status", "completed");
    }

    @Test
    void a_run_that_waited_until_its_token_could_not_cover_the_deadline_is_not_started() {
        // The only worker is held by a first run until the test lets it go.
        Sinks.Many<LlmClient.LlmEvent> held = Sinks.many().unicast().onBackpressureBuffer();
        llm.round(held.asFlux());
        String first = accepted("¿Cuánto se vendió ayer?");
        long until = System.currentTimeMillis() + 10_000;
        while (!"running".equals(get(first).get("status")) || llm.requests().isEmpty()) {
            assertThat(System.currentTimeMillis()).isLessThan(until);
            sleep(40);
        }
        long before = usage.usage(tenant, null).messages();
        int modelCalls = llm.requests().size();

        // Admitted: 32 seconds of token for a 30-second deadline. It waits behind the first run.
        String waiting = accepted(body(runToken("u-" + UUID.randomUUID()), 30,
                Instant.now().plusSeconds(32).toString()));
        assertThat(get(waiting)).containsEntry("status", "queued");
        assertThat(usage.usage(tenant, null).messages()).isEqualTo(before + 1);
        sleep(3_000);
        held.tryEmitNext(new LlmClient.LlmEvent.TokenChunk("Listo."));
        held.tryEmitNext(new LlmClient.LlmEvent.Done(LlmClient.StopReason.END_TURN, ScriptedLlmClient.ROUND_USAGE));
        held.tryEmitComplete();
        assertThat(finished(first)).containsEntry("status", "completed");

        Map<String, Object> run = finished(waiting);

        assertThat(run).containsEntry("status", "failed").containsKey("finishedAt").doesNotContainKeys("answer", "startedAt");
        assertThat(failureCode(run)).isEqualTo("token_expiring");
        assertThat(run.get("tools")).isEqualTo(java.util.List.of());
        assertThat(String.valueOf(run.get("usage"))).contains("rounds=0").contains("toolCalls=0");
        // Never started: no conversation, no model call, nothing reached the backend, the message went back.
        assertThat(sessionOf(waiting)).isNull();
        assertThat(llm.requests()).hasSize(modelCalls);
        assertThat(backend.getAllServeEvents()).isEmpty();
        assertThat(usage.usage(tenant, null).messages()).isEqualTo(before);
        // Its place is free again.
        llm.say("Listo.");
        assertThat(runToEnd("¿Cuánto se vendió ayer?")).containsEntry("status", "completed");
    }
}
