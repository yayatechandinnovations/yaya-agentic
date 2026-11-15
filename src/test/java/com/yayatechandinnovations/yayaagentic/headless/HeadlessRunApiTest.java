package com.yayatechandinnovations.yayaagentic.headless;

import com.yayatechandinnovations.yayaagentic.auth.Authenticator;
import com.yayatechandinnovations.yayaagentic.auth.PrincipalKinds;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatRateLimiter;
import com.yayatechandinnovations.yayaagentic.config.SessionsApiProperties;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.engine.ConversationEngine;
import com.yayatechandinnovations.yayaagentic.llm.LlmClient;
import com.yayatechandinnovations.yayaagentic.llm.StubLlmClient;
import com.yayatechandinnovations.yayaagentic.profile.Profile;
import com.yayatechandinnovations.yayaagentic.testsupport.ScriptedLlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Sinks;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code POST /v1/headless/runs} and {@code GET /v1/headless/runs/{runId}}
 * over the real stack ({@code docs/guides/admin-panel-sessions.md} §17): what
 * is accepted, each refusal and its code, and that a run is its tenant's.
 */
class HeadlessRunApiTest extends HeadlessRunTestBase {

    @Autowired ConversationEngine engine;
    @Autowired Authenticator authenticator;
    @Autowired PrincipalKinds principalKinds;
    @Autowired ChatRateLimiter rateLimiter;
    @Autowired Clock clock;
    @Autowired HeadlessRunProperties props;
    @Autowired SessionsApiProperties sessionsApi;

    // ---- accepted -----------------------------------------------------------

    @Test
    void a_run_is_accepted_queued_and_answers_when_polled() {
        llm.tool("c1", "hl_me", Map.of());
        llm.say("Ayer se vendieron RD$1,250.00.");

        byte[] raw = submit(body(runToken("u-" + UUID.randomUUID()), "¿Cuánto se vendió ayer?"))
                .expectStatus().isAccepted()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectBody().returnResult().getResponseBody();
        Map<String, Object> accepted = read(raw);
        assertThat(accepted).containsOnlyKeys("runId", "status").containsEntry("status", "queued");
        String runId = (String) accepted.get("runId");
        assertThat(runId).matches("hr_[0-9A-HJKMNP-TV-Z]{26}");

        Map<String, Object> run = finished(runId);
        assertThat(run).containsOnlyKeys("runId", "status", "answer", "usage", "tools", "startedAt", "finishedAt")
                .containsEntry("runId", runId).containsEntry("status", "completed")
                .containsEntry("answer", "Ayer se vendieron RD$1,250.00.")
                .containsEntry("tools", List.of("hl_me"));
        assertThat(run.get("usage")).isEqualTo(Map.of("inputTokens", 200, "outputTokens", 40,
                "cacheReadTokens", 0, "rounds", 2, "toolCalls", 1));
        assertThat((String) run.get("startedAt")).matches("\\d{4}-\\d{2}-\\d{2}T.*Z");

        // What is stored about it: the profile version that ran, who it was for, the context as sent.
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM headless_runs WHERE id = ?", runId);
        assertThat(row).containsEntry("tenant_id", tenant).containsEntry("profile_id", PROFILE)
                .containsEntry("profile_version", 1).containsEntry("question", "¿Cuánto se vendió ayer?");
        assertThat((String) row.get("context_json")).contains("\"automationName\":\"Resumen del lunes\"");
    }

    @Test
    void a_failed_run_carries_its_failure_and_no_answer() {
        llm.tool("c1", "hl_probe", Map.of("how", "confirm"));

        Map<String, Object> run = runToEnd("¿Cómo va la 12?");

        assertThat(run).containsOnlyKeys("runId", "status", "failure", "usage", "tools", "startedAt", "finishedAt")
                .containsEntry("status", "failed");
        assertThat(cast(run.get("failure"))).containsOnlyKeys("code", "message")
                .containsEntry("code", "needs_interaction");
    }

    @Test
    void the_same_idem_key_names_the_same_run_and_runs_it_once() {
        String key = "summary:exec-" + run + ":1";
        llm.say("Ayer se vendieron RD$1,250.00.");
        Map<String, Object> first = body(runToken("u-" + UUID.randomUUID()), "¿Cuánto se vendió ayer?");

        Map<String, Object> one = read(submit(serviceToken(), key, first).expectStatus().isAccepted()
                .expectBody().returnResult().getResponseBody());
        finished((String) one.get("runId"));
        // Sent again — with another token and another question: the same body comes back.
        Map<String, Object> two = read(submit(serviceToken(), key,
                body(runToken("u-" + UUID.randomUUID()), "otra pregunta")).expectStatus().isAccepted()
                .expectBody().returnResult().getResponseBody());

        assertThat(two).isEqualTo(one);
        assertThat(llm.requests()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM headless_runs WHERE tenant_id = ? AND idem_key = ?",
                Integer.class, tenant, key)).isEqualTo(1);

        // Past 24 hours the key names a new run.
        jdbc.update("UPDATE headless_runs SET created_at = NOW() - INTERVAL '25 hours' WHERE id = ?", one.get("runId"));
        llm.say("Hoy se vendieron RD$900.00.");
        Map<String, Object> later = read(submit(serviceToken(), key,
                body(runToken("u-" + UUID.randomUUID()), "¿Cuánto se vendió hoy?")).expectStatus().isAccepted()
                .expectBody().returnResult().getResponseBody());
        assertThat(later.get("runId")).isNotEqualTo(one.get("runId"));
        assertThat(finished((String) later.get("runId"))).containsEntry("answer", "Hoy se vendieron RD$900.00.");
    }

    // ---- 401 / 403 on the service token -------------------------------------

    @Test
    void no_service_token_or_a_forged_one_is_401() {
        Map<String, Object> body = body(runToken("u-1"), "¿Cuánto se vendió ayer?");

        expectError(submit(null, "k1", body), HttpStatus.UNAUTHORIZED, "unauthorized");
        expectError(submit("yk_live_abc12345xyz9q7w3e8r2t5y6u1i4o0p7a3", "k1", body),
                HttpStatus.UNAUTHORIZED, "unauthorized");
        // The person's own token is not a service token.
        expectError(submit(runToken("u-1"), "k1", body), HttpStatus.UNAUTHORIZED, "unauthorized");
        expectError(poll("not-a-jwt", "hr_X"), HttpStatus.UNAUTHORIZED, "unauthorized");
        assertThat(llm.requests()).isEmpty();
    }

    @Test
    void a_service_token_without_the_scope_is_403() {
        expectError(submit(serviceToken(serviceIss, "structured:complete"), "k1",
                body(runToken("u-1"), "¿Cuánto se vendió ayer?")), HttpStatus.FORBIDDEN, "forbidden");
        expectError(poll(serviceToken(serviceIss, null), "hr_X"), HttpStatus.FORBIDDEN, "forbidden");
    }

    // ---- 403 principal_rejected / profile_not_permitted, 404 ----------------

    @Test
    void a_principal_token_that_is_not_a_read_only_automation_token_is_rejected() {
        String subject = "u-" + UUID.randomUUID();
        List<String> refused = new ArrayList<>(List.of(
                "not-a-jwt",
                // An ordinary staff token: valid, and able to write.
                staffToken(subject, Map.of()),
                // An agent session, or an automation token that is not read-only.
                staffToken(subject, Map.of("via", "agent", "viaReadOnly", true)),
                staffToken(subject, Map.of("via", "automation")),
                staffToken(subject, Map.of("via", "automation", "viaReadOnly", false)),
                // Another business of the same install: the tenant's claim pin fails.
                staffToken(staffIss, "org-someone-else", subject, Map.of("via", "automation", "viaReadOnly", true)),
                // An issuer the tenant does not trust.
                staffToken("hl-identity-nobody", business, subject, Map.of("via", "automation", "viaReadOnly", true)),
                // Signed with another key.
                sign("a-key-the-tenant-never-shared-with-anyone-32+", new com.nimbusds.jwt.JWTClaimsSet.Builder()
                        .issuer(staffIss).subject(subject).claim("org", business).claim("via", "automation")
                        .claim("viaReadOnly", true)
                        .expirationTime(new java.util.Date(System.currentTimeMillis() + 60_000)).build()),
                // Expired.
                sign(STAFF_SECRET, new com.nimbusds.jwt.JWTClaimsSet.Builder()
                        .issuer(staffIss).subject(subject).claim("org", business).claim("via", "automation")
                        .claim("viaReadOnly", true)
                        .expirationTime(new java.util.Date(System.currentTimeMillis() - 60_000)).build())));

        for (String token : refused) {
            expectError(submit(body(token, "¿Cuánto se vendió ayer?")), HttpStatus.FORBIDDEN, "principal_rejected");
        }
        assertThat(llm.requests()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM headless_runs WHERE tenant_id = ? AND question = ?",
                Integer.class, tenant, "¿Cuánto se vendió ayer?")).isZero();
    }

    @Test
    void a_profile_that_does_not_admit_the_principal_is_403_and_an_unknown_one_is_404() {
        profiles.register(new Profile(new Ids.ProfileId("hl_other_kind", 1), new Ids.TenantId(tenant), "Otro",
                "Hola.", "You are another assistant.", List.of(new Ids.CapabilityId("hl-cap")), List.of(), null,
                "es", Map.of("principalKinds", List.of("TestIdentity/player")), Map.of(), null,
                new Profile.Runtime(null, 8, true)));

        Map<String, Object> otherKind = body(runToken("u-1"), "¿Cuánto se vendió ayer?");
        otherKind.put("profileId", "hl_other_kind");
        expectError(submit(otherKind), HttpStatus.FORBIDDEN, "profile_not_permitted");

        Map<String, Object> unknown = body(runToken("u-1"), "¿Cuánto se vendió ayer?");
        unknown.put("profileId", "no_such_profile");
        expectError(submit(unknown), HttpStatus.NOT_FOUND, "profile_not_found");
        assertThat(llm.requests()).isEmpty();
    }

    // ---- 409 replay -----------------------------------------------------------

    @Test
    void a_request_sent_twice_or_without_its_nonce_and_timestamp_is_409_replay() {
        String nonce = UUID.randomUUID().toString();
        String now = String.valueOf(System.currentTimeMillis());
        llm.say("Listo.");

        Map<String, Object> first = read(raw("k-replay-" + run, nonce, now).expectStatus().isAccepted()
                .expectBody().returnResult().getResponseBody());
        finished((String) first.get("runId"));
        expectError(raw("k-replay-" + run, nonce, now), HttpStatus.CONFLICT, "replay");
        expectError(raw("k-replay-2", null, now), HttpStatus.CONFLICT, "replay");
        expectError(raw("k-replay-3", UUID.randomUUID().toString(), null), HttpStatus.CONFLICT, "replay");
        expectError(raw("k-replay-4", UUID.randomUUID().toString(), "yesterday"), HttpStatus.CONFLICT, "replay");
        expectError(raw("k-replay-5", UUID.randomUUID().toString(),
                String.valueOf(System.currentTimeMillis() - 6 * 60_000)), HttpStatus.CONFLICT, "replay");
        // A poll is guarded the same way.
        expectError(client.get().uri("/v1/headless/runs/" + first.get("runId"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken())
                .header("X-Nonce", nonce).header("X-Timestamp", now).exchange(), HttpStatus.CONFLICT, "replay");
        assertThat(llm.requests()).hasSize(1);
    }

    // ---- 422 ------------------------------------------------------------------

    @Test
    void bad_requests_are_422_invalid_request() {
        String token = runToken("u-1");
        List<Object> bad = new ArrayList<>(List.of(
                "not json".getBytes(StandardCharsets.UTF_8),
                "[]".getBytes(StandardCharsets.UTF_8),
                with(token, b -> b.remove("profileId")),
                with(token, b -> b.remove("principalToken")),
                with(token, b -> b.put("principalToken", 7)),
                with(token, b -> b.remove("question")),
                with(token, b -> b.put("question", "   ")),
                with(token, b -> b.put("question", "x".repeat(2001))),
                with(token, b -> b.put("timeZone", "Santo Domingo")),
                with(token, b -> b.put("context", "lunes")),
                with(token, b -> b.put("limits", Map.of("deadlineSeconds", 29))),
                with(token, b -> b.put("limits", Map.of("deadlineSeconds", 301))),
                with(token, b -> b.put("limits", Map.of("maxAnswerChars", 199))),
                with(token, b -> b.put("limits", Map.of("maxAnswerChars", 4001))),
                with(token, b -> b.put("limits", Map.of("maxAnswerChars", "muchos")))));

        for (Object body : bad) {
            expectError(submit(body), HttpStatus.UNPROCESSABLE_ENTITY, "invalid_request");
        }
        // The key is part of the request.
        expectError(submit(serviceToken(), null, body(token, "¿Cuánto se vendió ayer?")),
                HttpStatus.UNPROCESSABLE_ENTITY, "invalid_request");
        expectError(submit(serviceToken(), "k".repeat(121), body(token, "¿Cuánto se vendió ayer?")),
                HttpStatus.UNPROCESSABLE_ENTITY, "invalid_request");
        assertThat(llm.requests()).isEmpty();

        // The limits are optional, and the edges are inside.
        llm.say("Listo.");
        Map<String, Object> edges = with(token, b -> b.put("limits", Map.of("maxAnswerChars", 200, "deadlineSeconds", 30)));
        assertThat(finished(accepted(edges))).containsEntry("status", "completed");
        llm.say("Listo.");
        Map<String, Object> bare = new LinkedHashMap<>(Map.of("profileId", PROFILE, "principalToken", token,
                "question", "¿Cuánto se vendió ayer?"));
        assertThat(finished(accepted(bare))).containsEntry("status", "completed");
    }

    // ---- 429 ------------------------------------------------------------------

    @Test
    void a_third_run_while_two_of_the_tenant_are_in_flight_is_429_busy() {
        Sinks.Many<LlmClient.LlmEvent> one = Sinks.many().unicast().onBackpressureBuffer();
        Sinks.Many<LlmClient.LlmEvent> two = Sinks.many().unicast().onBackpressureBuffer();
        llm.round(one.asFlux());
        llm.round(two.asFlux());
        String first = accepted("¿Cuánto se vendió ayer?");
        String second = accepted("¿Cuánto se vendió hoy?");
        try {
            submit(body(runToken("u-1"), "¿Y mañana?"))
                    .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                    .expectHeader().exists(HttpHeaders.RETRY_AFTER)
                    .expectBody().jsonPath("$.code").isEqualTo("busy");
        } finally {
            for (Sinks.Many<LlmClient.LlmEvent> model : List.of(one, two)) {
                model.tryEmitNext(new LlmClient.LlmEvent.TokenChunk("Listo."));
                model.tryEmitNext(new LlmClient.LlmEvent.Done(LlmClient.StopReason.END_TURN,
                        ScriptedLlmClient.ROUND_USAGE));
                model.tryEmitComplete();
            }
        }
        assertThat(finished(first)).containsEntry("status", "completed");
        assertThat(finished(second)).containsEntry("status", "completed");
        // The refused one took no message and left no row; with room again it is accepted.
        llm.say("Listo.");
        assertThat(runToEnd("¿Y mañana?")).containsEntry("status", "completed");
    }

    @Test
    void more_than_twenty_runs_of_a_tenant_in_an_hour_is_429_rate_limited() {
        String other = "hl-rl-" + run;
        installTenant(other, "org-rl-" + run, "hl-identity-rl-" + run, "hl-sender-rl-" + run);
        registerProfile(other, 8, null);
        String service = serviceToken("hl-sender-rl-" + run, "headless:run");

        for (int i = 0; i < 20; i++) {
            llm.say("Listo.");
            Map<String, Object> ok = read(submit(service, "k-" + i, body(staffToken("hl-identity-rl-" + run,
                    "org-rl-" + run, "u-" + i, Map.of("via", "automation", "viaReadOnly", true)), "¿Cuánto?"))
                    .expectStatus().isAccepted().expectBody().returnResult().getResponseBody());
            awaitDone(other, (String) ok.get("runId"));
        }
        submit(service, "k-21", body(staffToken("hl-identity-rl-" + run, "org-rl-" + run, "u-21",
                Map.of("via", "automation", "viaReadOnly", true)), "¿Cuánto?"))
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().exists(HttpHeaders.RETRY_AFTER)
                .expectBody().jsonPath("$.code").isEqualTo("rate_limited");
        // The limit is the tenant's own.
        llm.say("Listo.");
        assertThat(runToEnd("¿Cuánto se vendió ayer?")).containsEntry("status", "completed");
    }

    // ---- 503 ------------------------------------------------------------------

    @Test
    void a_profile_with_no_tool_a_run_may_call_is_422_no_headless_tools_and_takes_nothing() {
        // A profile of the tenant none of whose tools a headless run may call:
        // two that are not flagged and one flagged against the catalogue's rule.
        String plain = "hl_plain";
        Ids.CapabilityId cap = new Ids.CapabilityId("hl-cap-plain-" + run);
        catalog.registerCapability(new com.yayatechandinnovations.yayaagentic.profile.Capability(cap, "Clientes",
                "Clientes y notas", "Use the tools.",
                List.of(new Ids.ToolId("hl_customers"), new Ids.ToolId("memory_save"),
                        new Ids.ToolId("hl_toggle_flagged")),
                List.of(), com.yayatechandinnovations.yayaagentic.core.PermissionRequirement.none()));
        profiles.register(new Profile(new Ids.ProfileId(plain, 1), new Ids.TenantId(tenant), "Clientes",
                "Hola.", "You are the customers assistant.", List.of(cap), List.of(), null, "es",
                Map.of("principalKinds", List.of(STAFF)), Map.of(), null, new Profile.Runtime(null, 8, true)));
        long messages = usage.usage(tenant, null).messages();
        int rows = rowsOfTenant();
        // What the model would say with no tool to ask: it must never be asked.
        llm.say("Ayer se vendieron RD$9,999.00.");

        Map<String, Object> body = body(runToken("u-" + UUID.randomUUID()), "¿Cuánto se vendió ayer?");
        body.put("profileId", plain);
        expectError(submit(body), HttpStatus.UNPROCESSABLE_ENTITY, "no_headless_tools");

        assertThat(rowsOfTenant()).isEqualTo(rows);
        assertThat(llm.requests()).isEmpty();
        assertThat(usage.usage(tenant, null).messages()).isEqualTo(messages);

        // A key that already names a run of that profile keeps answering with it.
        String earlier = "hr_TESTPLAIN" + run.toUpperCase();
        String key = "summary:plain-" + run + ":1";
        store.insertQueued(earlier, tenant, plain, 1, key, "u-1", "¿Cuánto se vendió?", null, java.time.Instant.now());
        try {
            assertThat(read(submit(serviceToken(), key, body).expectStatus().isAccepted()
                    .expectBody().returnResult().getResponseBody())).containsEntry("runId", earlier);
        } finally {
            store.fail(tenant, earlier, "interrupted", "test", null, null, java.time.Instant.now());
        }
    }

    private int rowsOfTenant() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM headless_runs WHERE tenant_id = ?", Integer.class, tenant);
        return n == null ? 0 : n;
    }

    @Test
    void a_deployment_with_no_model_configured_is_503_llm_unavailable() {
        // The same service over the offline stub provider, as a deployment without a model has it.
        HeadlessRunService offline = new HeadlessRunService(store, engine, authenticator, profiles, principalKinds,
                usage, rateLimiter, new StubLlmClient(), json, clock, props, sessionsApi);
        try {
            long before = usage.usage(tenant, null).messages();
            byte[] body = json.writeValueAsBytes(body(runToken("u-1"), "¿Cuánto se vendió ayer?"));

            assertThatThrownBy(() -> offline.submit(tenant, "k-offline-" + run, body))
                    .isInstanceOfSatisfying(HeadlessRunException.class, e -> {
                        assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                        assertThat(e.code()).isEqualTo("llm_unavailable");
                        assertThat(e.body()).containsEntry("code", "llm_unavailable");
                    });
            assertThat(usage.usage(tenant, null).messages()).isEqualTo(before);
            assertThat(store.findByIdemKey(tenant, "k-offline-" + run)).isEmpty();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        } finally {
            offline.shutdown();
        }
    }

    // ---- another tenant ----------------------------------------------------

    @Test
    void a_run_of_another_tenant_or_an_unknown_one_is_404_run_not_found() {
        String other = "hl-b-" + run;
        installTenant(other, "org-b-" + run, "hl-identity-b-" + run, "hl-sender-b-" + run);
        llm.say("Ayer se vendieron RD$1,250.00.");
        String runId = accepted("¿Cuánto se vendió ayer?");
        finished(runId);

        expectError(poll(serviceToken("hl-sender-b-" + run, "headless:run"), runId),
                HttpStatus.NOT_FOUND, "run_not_found");
        expectError(poll(serviceToken(), "hr_00000000000000000000000000"), HttpStatus.NOT_FOUND, "run_not_found");
        // The tenant is the token's: the other tenant's service cannot start a run on this one's profile either.
        expectError(submit(serviceToken("hl-sender-b-" + run, "headless:run"), "k-b",
                body(runToken("u-1"), "¿Cuánto se vendió ayer?")), HttpStatus.NOT_FOUND, "profile_not_found");
        assertThat(get(runId)).containsEntry("status", "completed");
    }

    // ---- helpers ----------------------------------------------------------

    private WebTestClient.ResponseSpec raw(String idemKey, String nonce, String timestamp) {
        WebTestClient.RequestBodySpec spec = client.post().uri("/v1/headless/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken())
                .header("X-Idem-Key", idemKey);
        if (nonce != null) spec = spec.header("X-Nonce", nonce);
        if (timestamp != null) spec = spec.header("X-Timestamp", timestamp);
        return spec.bodyValue(body(runToken("u-" + UUID.randomUUID()), "¿Cuánto se vendió ayer?")).exchange();
    }

    private Map<String, Object> with(String token, java.util.function.Consumer<Map<String, Object>> change) {
        Map<String, Object> body = body(token, "¿Cuánto se vendió ayer?");
        change.accept(body);
        return body;
    }

    private void awaitDone(String tenantId, String runId) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (true) {
            String status = store.find(tenantId, runId).orElseThrow().status();
            if ("completed".equals(status) || "failed".equals(status)) return;
            if (System.currentTimeMillis() > deadline) throw new AssertionError("run " + runId + " still " + status);
            sleep(20);
        }
    }

    private static void expectError(WebTestClient.ResponseSpec response, HttpStatus status, String code) {
        response.expectStatus().isEqualTo(status)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody().jsonPath("$.code").isEqualTo(code).jsonPath("$.message").isNotEmpty();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object map) {
        return (Map<String, Object>) map;
    }
}
