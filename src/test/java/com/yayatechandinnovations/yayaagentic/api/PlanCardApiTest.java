package com.yayatechandinnovations.yayaagentic.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.auth.jwt.TenantJwtSecretsChangedEvent;
import com.yayatechandinnovations.yayaagentic.auth.secrets.JwtSecretCipher;
import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.core.TurnChannel;
import com.yayatechandinnovations.yayaagentic.engine.ConversationEngine;
import com.yayatechandinnovations.yayaagentic.engine.TurnEvent;
import com.yayatechandinnovations.yayaagentic.engine.UserMessage;
import com.yayatechandinnovations.yayaagentic.engine.bootstrap.M0Catalog;
import com.yayatechandinnovations.yayaagentic.memory.WorkingMemory;
import com.yayatechandinnovations.yayaagentic.persistence.TenantEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantJwtSecretEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantJwtSecretRepository;
import com.yayatechandinnovations.yayaagentic.persistence.TenantRepository;
import com.yayatechandinnovations.yayaagentic.profile.Capability;
import com.yayatechandinnovations.yayaagentic.profile.Profile;
import com.yayatechandinnovations.yayaagentic.profile.ProfileRegistry;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import com.yayatechandinnovations.yayaagentic.tenant.TenantOrigins;
import com.yayatechandinnovations.yayaagentic.llm.LlmClient;
import com.yayatechandinnovations.yayaagentic.testsupport.ScriptedLlmClient;
import com.yayatechandinnovations.yayaagentic.tool.ToolDescriptor;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandler;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandlerRef;
import com.yayatechandinnovations.yayaagentic.tool.ToolPolicy;
import com.yayatechandinnovations.yayaagentic.usage.UsageMeter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * Several changes in one model answer, {@code docs/guides/admin-panel-sessions.md}
 * §16 and §5: one plan card for the calls that may share one, one card and
 * {@code deferred} results otherwise, a turn that ends on its card, and a
 * card that runs once however many answers it gets.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import({TestcontainersConfiguration.class, PlanCardApiTest.Config.class})
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key=",
        "spring.main.allow-bean-definition-overriding=true",
        "yaya.agentic.secrets.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "yaya.agentic.sessions.messages-per-minute=400",
        "yaya.agentic.sessions.session-starts-per-minute=400"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlanCardApiTest {

    private static final String ISS = "plan-test-identity";
    private static final String SECRET = "the-install-signing-key-for-the-plan-card-test";
    private static final String STAFF = "TestIdentity/staff";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    @Autowired WebTestClient client;
    @Autowired ScriptedLlmClient llm;
    @Autowired StepTool tool;
    @Autowired TenantRepository tenants;
    @Autowired TenantJwtSecretRepository jwtSecrets;
    @Autowired JwtSecretCipher cipher;
    @Autowired ApplicationEventPublisher events;
    @Autowired ProfileRegistry profiles;
    @Autowired M0Catalog catalog;
    @Autowired TenantOrigins tenantOrigins;
    @Autowired ConversationEngine engine;
    @Autowired WorkingMemory workingMemory;
    @Autowired ObjectMapper json;
    @Autowired SecretPromptApiTest.GatedWorkingMemory gate;
    @Autowired UsageMeter usage;

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final String tenant = "plan-" + run;
    private final String org = "org-" + run;

    @BeforeAll
    void install() {
        TenantEntity t = new TenantEntity(tenant, "Plan " + tenant);
        t.setHostBaseUrl("https://" + tenant + ".example.com");
        tenants.save(t);
        JwtSecretCipher.Encrypted enc = cipher.encrypt(SECRET);
        TenantJwtSecretEntity trust = new TenantJwtSecretEntity(tenant, ISS, enc.ciphertext(), enc.iv(), "[]");
        trust.setPrincipalKind(STAFF);
        trust.setRequiredClaimsJson("{\"org\":\"" + org + "\"}");
        jwtSecrets.save(trust);
        events.publishEvent(new TenantJwtSecretsChangedEvent(tenant));
        tenantOrigins.evict();

        List<Ids.ToolId> ids = new ArrayList<>();
        ids.add(register("plan_stop", confirmable("¿Detener la venta en la estación {station}?", true, false)));
        ids.add(register("plan_notify", confirmable("¿Enviar el aviso «{title}» a la estación {station}?", true, false)));
        ids.add(register("plan_bare", confirmable(null, true, false)));
        // Marked both ways against the catalogue's rule: its prompt must not stop a plan.
        ids.add(register("plan_coded", confirmable("¿Pagar «{what}»?", true, true)));
        ids.add(register("plan_apply", confirmable("¿Aplicar «{what}»?", false, false)));
        ids.add(register("plan_create", confirmable("¿Crear «{what}»?", false, false)));
        ids.add(register("plan_code", new ToolPolicy(Duration.ofSeconds(10), 0, false, false, false, null,
                false, false, false, true)));
        ids.add(register("plan_read", ToolPolicy.defaults()));
        catalog.registerCapability(new Capability(new Ids.CapabilityId("plan-cap-" + run), "Plan", "Plan tools",
                "Use the tools.", ids, List.of(), PermissionRequirement.none()));
        profiles.register(new Profile(new Ids.ProfileId("plan_owner", 1), new Ids.TenantId(tenant), "Plan owner",
                "Hola.", "You are the operations assistant.",
                List.of(new Ids.CapabilityId("plan-cap-" + run)), List.of(), null, "es",
                Map.of("principalKinds", List.of(STAFF)), Map.of(), null, new Profile.Runtime(null, 8, true)));
    }

    private Ids.ToolId register(String id, ToolPolicy policy) {
        catalog.registerTool(new ToolDescriptor(new Ids.ToolId(id),
                "{\"type\":\"object\"}", "{\"type\":\"object\"}",
                PermissionRequirement.none(), new ToolHandlerRef.Bean("planStepTool"), policy));
        return new Ids.ToolId(id);
    }

    private static ToolPolicy confirmable(String prompt, boolean plannable, boolean secretInput) {
        return new ToolPolicy(Duration.ofSeconds(10), 0, false, true, false, prompt,
                false, false, false, secretInput, plannable);
    }

    @BeforeEach
    void reset() {
        llm.reset();
        tool.reset();
        gate.open();
    }

    // ------------------------------------------------------------------
    // Found in stages 1–2 (contract 3A §2.4): reproduced first, then fixed
    // ------------------------------------------------------------------

    @Test
    void a_plain_call_next_to_a_confirmable_one_ends_the_turn_and_every_call_gets_one_result() {
        String token = token("u-" + UUID.randomUUID());
        String sid = start(token);

        llm.round(answer(call("p1", "plan_read", Map.of("what", "ventas")),
                call("c1", "plan_apply", Map.of("what", "Especial 80"))));
        List<Sse> proposed = send(sid, token, Map.of("text", "dime las ventas y aplica Especial 80"));

        // The read ran, the change waits on its card, and the model was not
        // asked again with a call that has no result.
        assertThat(llm.requests()).hasSize(1);
        assertThat(proposed).extracting(Sse::event)
                .containsExactly("tool_call", "tool_result", "tool_call", "ui_hint", "token", "end");
        assertThat(proposed.get(3).data().get("kind")).isEqualTo("confirm");
        assertThat(tool.ran()).containsExactly("ventas");
        String card = (String) payload(proposed.get(3)).get("callId");

        llm.say("Listo: apliqué Especial 80.");
        List<Sse> confirmed = send(sid, token,
                Map.of("text", "", "selection", Map.of("kind", "confirm", "callId", card)));
        assertThat(confirmed).extracting(Sse::event).containsExactly("tool_call", "tool_result", "token", "end");
        assertThat(tool.ran()).containsExactly("ventas", "Especial 80");
        assertEveryCallHasOneResult(llm.lastRequest());
        assertThat(callsIn(llm.lastRequest())).containsExactly("plan_read", "plan_apply");
    }

    @Test
    void two_confirmable_calls_that_cannot_share_a_card_get_one_card_and_a_deferred_result() {
        String token = token("u-" + UUID.randomUUID());
        String sid = start(token);

        llm.round(answer(call("c1", "plan_apply", Map.of("what", "uno")),
                call("c2", "plan_create", Map.of("what", "dos"))));
        List<Sse> proposed = send(sid, token, Map.of("text", "aplica uno y crea dos"));

        // One card — the first — and one pending slot. The second did not run.
        assertThat(proposed).filteredOn(e -> "ui_hint".equals(e.event())).singleElement()
                .satisfies(h -> assertThat(payload(h)).containsEntry("toolId", "plan_apply"));
        Map<String, Object> deferred = resultOf(proposed, "c2");
        assertThat(deferred).containsEntry("status", "OK");
        assertThat(cast((Map<?, ?>) deferred.get("value"))).containsEntry("ok", false)
                .containsEntry("reason", "deferred")
                .containsEntry("userSafeMessage", "Este cambio se confirma aparte, después.");
        assertThat(tool.ran()).isEmpty();
        assertThat(llm.requests()).hasSize(1);
        Map<String, Object> latest = latest(token);
        assertThat(cast((Map<?, ?>) latest.get("pendingConfirm"))).containsEntry("toolId", "plan_apply");
        // A deferred call did not run: it is not reported as a finished one.
        assertThat((List<?>) latest.get("lastToolResults")).isEmpty();

        // The card that was shown is the one that answers.
        String card = (String) cast((Map<?, ?>) latest.get("pendingConfirm")).get("callId");
        llm.say("Apliqué uno.");
        List<Sse> confirmed = send(sid, token,
                Map.of("text", "", "selection", Map.of("kind", "confirm", "callId", card)));
        assertThat(confirmed).extracting(Sse::event).containsExactly("tool_call", "tool_result", "token", "end");
        assertThat(tool.ran()).containsExactly("uno");
        assertEveryCallHasOneResult(llm.lastRequest());
    }

    // ------------------------------------------------------------------
    // One plan card
    // ------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void two_plannable_calls_become_one_plan_that_runs_in_order_and_is_reported_once() {
        String token = token("u-" + UUID.randomUUID());
        String sid = start(token);
        long counted = usage.usage(tenant, null).messages();

        llm.round(Flux.just(new LlmClient.LlmEvent.TokenChunk("Te lo dejo en un solo paso."),
                stop("s1", "12"), notify("s2", "Cierre", "07"),
                new LlmClient.LlmEvent.Done(LlmClient.StopReason.TOOL_USE, ScriptedLlmClient.ROUND_USAGE)));
        List<Sse> proposed = send(sid, token, Map.of("text", "para la venta en la 12 y avísale a la 07"));

        // One frame, no confirm frame, no call of a step, nothing run.
        assertThat(proposed).extracting(Sse::event).containsExactly("token", "ui_hint", "token", "end");
        assertThat(proposed.get(1).data().get("kind")).isEqualTo("plan");
        Map<String, Object> plan = payload(proposed.get(1));
        String text = "Voy a hacer esto, en orden:\n1. Detener la venta en la estación 12\n"
                + "2. Enviar el aviso «Cierre» a la estación 07\n¿Lo hago?";
        assertThat(plan).containsOnlyKeys("callId", "title", "steps", "confirmLabel", "cancelLabel", "canDrop", "text")
                .containsEntry("title", "Voy a hacer esto, en orden:")
                .containsEntry("confirmLabel", "Hacer todo").containsEntry("cancelLabel", "Cancelar")
                .containsEntry("canDrop", true).containsEntry("text", text);
        assertThat((List<Map<String, Object>>) plan.get("steps")).containsExactly(
                Map.of("id", "1", "toolId", "plan_stop", "summary", "Detener la venta en la estación 12"),
                Map.of("id", "2", "toolId", "plan_notify", "summary", "Enviar el aviso «Cierre» a la estación 07"));
        // The same list as text, on a line of its own after the model's words.
        assertThat(proposed.get(2).data().get("text")).isEqualTo("\n\n" + text);
        assertThat(tool.ran()).isEmpty();
        assertThat(llm.requests()).hasSize(1);
        String planId = (String) plan.get("callId");

        // One pending slot, and the card is found again on reopening.
        Map<String, Object> wm = workingMemory.get(new Ids.SessionId(sid));
        assertThat(wm).containsKey("pending_plan").doesNotContainKeys("pending_confirm", "pending_secret");
        Map<String, Object> latest = latest(token);
        assertThat(latest.get("pendingPlan")).isEqualTo(plan);
        assertThat(latest.get("pendingConfirm")).isNull();
        assertThat(latest.get("pendingSecret")).isNull();
        assertThat(history(sid, token).get("pendingPlan")).isEqualTo(plan);
        assertThat(String.valueOf(history(sid, token).get("turns"))).doesNotContain("\"plan\"");

        // «Hacer todo».
        llm.say("Hecho: detuve la venta en la 12 y envié el aviso a la 07.");
        List<Sse> done = send(sid, token,
                Map.of("text", "", "selection", Map.of("kind", "confirm", "callId", planId)));
        assertThat(done).extracting(Sse::event).containsExactly(
                "ui_hint", "tool_call", "tool_result", "ui_hint",
                "ui_hint", "tool_call", "tool_result", "ui_hint",
                "ui_hint", "token", "end");
        assertThat(payload(done.get(0))).isEqualTo(Map.of("callId", planId, "stepId", "1", "state", "running"));
        assertThat(payload(done.get(3))).isEqualTo(
                Map.of("callId", planId, "stepId", "1", "state", "done", "message", "Hecho: 12"));
        assertThat(payload(done.get(4))).isEqualTo(Map.of("callId", planId, "stepId", "2", "state", "running"));
        assertThat(payload(done.get(7))).containsEntry("stepId", "2").containsEntry("state", "done");
        assertThat(done.get(8).data().get("kind")).isEqualTo("plan_result");
        assertThat(payload(done.get(8))).containsOnlyKeys("callId", "steps").containsEntry("callId", planId);
        assertThat((List<Map<String, Object>>) payload(done.get(8)).get("steps")).containsExactly(
                Map.of("id", "1", "toolId", "plan_stop", "summary", "Detener la venta en la estación 12",
                        "state", "done", "message", "Hecho: 12"),
                Map.of("id", "2", "toolId", "plan_notify", "summary", "Enviar el aviso «Cierre» a la estación 07",
                        "state", "done", "message", "Hecho: Cierre"));

        // Each step is its tool's own call, under the id the model gave it:
        // what is derived from {session.id}-{turn.id}-{call.id} differs per step.
        assertThat(tool.ran()).containsExactly("12", "Cierre");
        assertThat(tool.inputs.get(0)).isEqualTo(Map.of("what", "12", "station", "12"));
        assertThat(done.get(1).data().get("callId")).isEqualTo("s1");
        assertThat(done.get(2).data().get("callId")).isEqualTo("s1");
        assertThat(done.get(5).data().get("callId")).isEqualTo("s2");
        assertThat(tool.turns).hasSize(2);
        assertThat(tool.turns.get(0)).isEqualTo(tool.turns.get(1));

        // One model round, told every result; the plan is gone; one message
        // for the proposal and one for the run.
        assertThat(llm.requests()).hasSize(2);
        assertEveryCallHasOneResult(llm.lastRequest());
        assertThat(callsIn(llm.lastRequest())).containsExactly("plan_stop", "plan_notify");
        assertThat(workingMemory.get(new Ids.SessionId(sid))).doesNotContainKey("pending_plan");
        assertThat(latest(token).get("pendingPlan")).isNull();
        assertThat(usage.usage(tenant, null).messages()).isEqualTo(counted + 2);
        // The steps that ran are the finished calls of the turn.
        assertThat((List<Map<String, Object>>) latest(token).get("lastToolResults"))
                .extracting(r -> r.get("callId")).containsExactly("s1", "s2");

        // The card is used up.
        List<Sse> again = send(sid, token,
                Map.of("text", "", "selection", Map.of("kind", "confirm", "callId", planId)));
        assertThat(again).extracting(Sse::event).containsExactly("token", "end");
        assertThat(again.get(0).data().get("text")).isEqualTo(
                "Esa confirmación ya no está vigente. Dime de nuevo qué quieres hacer.");
        assertThat(tool.ran()).hasSize(2);
    }

    @Test
    void a_change_that_cannot_be_a_step_is_deferred_next_to_the_plan() {
        String token = token("u-" + UUID.randomUUID());
        String sid = start(token);

        llm.round(answer(stop("s1", "12"), call("c1", "plan_apply", Map.of("what", "pago 80")),
                notify("s2", "Cierre", "07"), call("r1", "plan_read", Map.of("what", "ventas"))));
        List<Sse> proposed = send(sid, token, Map.of("text", "para la 12, sube el pago y avisa a la 07"));

        // The read ran, the change that needs its own card was told to wait,
        // and the two steps share the plan.
        assertThat(proposed).extracting(Sse::event).containsExactly(
                "tool_call", "tool_result", "tool_call", "tool_result", "ui_hint", "token", "end");
        assertThat(proposed.get(1).data()).containsEntry("callId", "r1").containsEntry("status", "OK");
        assertThat(cast((Map<?, ?>) resultOf(proposed, "c1").get("value"))).containsEntry("reason", "deferred");
        assertThat(proposed.get(4).data().get("kind")).isEqualTo("plan");
        assertThat(planStepTools(payload(proposed.get(4)))).containsExactly("plan_stop", "plan_notify");
        assertThat(tool.ran()).containsExactly("ventas");
        assertThat(llm.requests()).hasSize(1);
        assertThat(latest(token).get("pendingConfirm")).isNull();

        // After the plan the model reads "deferred" for the other change.
        llm.say("Detuve la 12 y avisé a la 07. El cambio de pago te lo confirmo aparte.");
        send(sid, token, Map.of("text", "", "selection",
                Map.of("kind", "confirm", "callId", payload(proposed.get(4)).get("callId"))));
        assertEveryCallHasOneResult(llm.lastRequest());
        assertThat(callsIn(llm.lastRequest())).containsExactly("plan_read", "plan_apply", "plan_stop", "plan_notify");
        assertThat(llm.lastRequest().history().toString()).contains("deferred");
        assertThat(tool.ran()).containsExactly("ventas", "12", "Cierre");
    }

    @Test
    void a_seventh_step_is_deferred_and_a_tool_without_a_question_is_named_by_its_id() {
        String token = token("u-" + UUID.randomUUID());
        String sid = start(token);

        llm.round(answer(stop("s1", "01"), stop("s2", "02"), stop("s3", "03"), stop("s4", "04"),
                call("s5", "plan_bare", Map.of("what", "05")), stop("s6", "06"), stop("s7", "07")));
        List<Sse> proposed = send(sid, token, Map.of("text", "para todas"));

        Map<String, Object> plan = payload(proposed.stream()
                .filter(e -> "ui_hint".equals(e.event())).findFirst().orElseThrow());
        assertThat(planStepTools(plan)).hasSize(6);
        assertThat(planSteps(plan).get(4)).containsEntry("id", "5").containsEntry("summary", "plan_bare");
        assertThat(planSteps(plan).get(5)).containsEntry("summary", "Detener la venta en la estación 06");
        assertThat(cast((Map<?, ?>) resultOf(proposed, "s7").get("value"))).containsEntry("reason", "deferred");
        assertThat(proposed).filteredOn(e -> "tool_result".equals(e.event())).hasSize(1);
        assertThat(tool.ran()).isEmpty();
    }

    // ------------------------------------------------------------------
    // Answers
    // ------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void only_the_ticked_steps_run_in_the_plans_order_and_unknown_ids_are_ignored() {
        String token = token("u-" + UUID.randomUUID());
        String sid = start(token);
        String planId = proposeThree(sid, token, null);

        llm.say("Detuve la 12 y la 15. El aviso no lo envié.");
        List<Sse> done = send(sid, token, Map.of("text", "", "selection",
                Map.of("kind", "confirm_items", "callId", planId, "itemIds", List.of("3", "9", "1"))));

        assertThat(tool.ran()).containsExactly("12", "15");
        assertThat(states(done)).containsExactly("1:running", "1:done", "2:skipped", "3:running", "3:done");
        List<Map<String, Object>> result = (List<Map<String, Object>>) payload(done.stream()
                .filter(e -> "plan_result".equals(e.data().get("kind"))).findFirst().orElseThrow()).get("steps");
        assertThat(result).extracting(s -> s.get("state")).containsExactly("done", "skipped", "done");
        assertThat(result.get(1)).doesNotContainKey("message");
        // The model is told the step that was left out did not run.
        assertEveryCallHasOneResult(llm.lastRequest());
        assertThat(resultsIn(llm.lastRequest())).hasSize(3);
        assertThat(String.valueOf(resultsIn(llm.lastRequest()).get(1).value())).contains("not_run");
    }

    @Test
    void an_empty_selection_and_a_cancel_run_nothing_and_do_not_call_the_model() {
        String token = token("u-" + UUID.randomUUID());
        String sid = start(token);

        String first = proposeThree(sid, token, null);
        int modelCalls = llm.requests().size();
        long counted = usage.usage(tenant, null).messages();
        List<Sse> none = send(sid, token, Map.of("text", "", "selection",
                Map.of("kind", "confirm_items", "callId", first, "itemIds", List.of())));
        assertThat(none).extracting(Sse::event).containsExactly("token", "end");
        assertThat(none.get(0).data().get("text")).isEqualTo("Listo, no hice nada.");
        assertThat(latest(token).get("pendingPlan")).isNull();

        String second = proposeThree(sid, token, null);
        modelCalls = llm.requests().size();
        counted = usage.usage(tenant, null).messages();
        List<Sse> cancelled = send(sid, token,
                Map.of("text", "", "selection", Map.of("kind", "cancel", "callId", second)));
        assertThat(cancelled).extracting(Sse::event).containsExactly("token", "end");
        assertThat(cancelled.get(0).data().get("text")).isEqualTo("Listo, no hice nada.");

        assertThat(tool.ran()).isEmpty();
        assertThat(llm.requests()).hasSize(modelCalls);
        assertThat(usage.usage(tenant, null).messages()).isEqualTo(counted);
        assertThat(latest(token).get("pendingPlan")).isNull();

        // Only ids the card never offered: nothing is left to run.
        String third = proposeThree(sid, token, null);
        List<Sse> foreign = send(sid, token, Map.of("text", "", "selection",
                Map.of("kind", "confirm_items", "callId", third, "itemIds", List.of("7", "x"))));
        assertThat(foreign.get(0).data().get("text")).isEqualTo("Listo, no hice nada.");
        assertThat(tool.ran()).isEmpty();
    }

    @Test
    void an_answer_to_a_plan_that_is_not_the_pending_one_runs_nothing() {
        String token = token("u-" + UUID.randomUUID());
        String sid = start(token);

        String old = proposeThree(sid, token, null);
        String current = proposeThree(sid, token, null);
        assertThat(current).isNotEqualTo(old);
        int modelCalls = llm.requests().size();

        for (String kind : List.of("confirm", "confirm_items", "cancel")) {
            List<Sse> stale = send(sid, token, Map.of("text", "", "selection",
                    Map.of("kind", kind, "callId", old, "itemIds", List.of("1"))));
            assertThat(stale).extracting(Sse::event).containsExactly("token", "end");
            assertThat(stale.get(0).data().get("text")).isEqualTo(
                    "Esa confirmación ya no está vigente. Dime de nuevo qué quieres hacer.");
        }
        assertThat(tool.ran()).isEmpty();
        assertThat(llm.requests()).hasSize(modelCalls);
        // The pending one is untouched by them.
        assertThat(cast((Map<?, ?>) latest(token).get("pendingPlan"))).containsEntry("callId", current);
    }

    // ------------------------------------------------------------------
    // Failure
    // ------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void the_first_step_that_answers_not_ok_stops_the_rest() {
        String token = token("u-" + UUID.randomUUID());
        String sid = start(token);
        String planId = proposeThree(sid, token, "refuse");

        llm.say("Detuve la 12. El aviso no salió, así que no llegué a detener la 15.");
        List<Sse> done = send(sid, token,
                Map.of("text", "", "selection", Map.of("kind", "confirm", "callId", planId)));

        assertThat(tool.ran()).containsExactly("12", "Cuadre");
        assertThat(states(done)).containsExactly("1:running", "1:done", "2:running", "2:failed", "3:skipped");
        List<Map<String, Object>> result = (List<Map<String, Object>>) payload(done.stream()
                .filter(e -> "plan_result".equals(e.data().get("kind"))).findFirst().orElseThrow()).get("steps");
        assertThat(result.get(1)).containsEntry("state", "failed").containsEntry("message", "No pude enviarlo ahora.");
        assertThat(result.get(2)).containsEntry("state", "skipped").doesNotContainKey("message");
        // Only two calls were made; the third has no tool_call.
        assertThat(done).filteredOn(e -> "tool_call".equals(e.event())).hasSize(2);

        assertEveryCallHasOneResult(llm.lastRequest());
        List<LlmClient.ToolResultSpec> told = resultsIn(llm.lastRequest());
        assertThat(told).hasSize(3);
        assertThat(String.valueOf(told.get(1).value())).contains("not_now");
        assertThat(told.get(2).value()).isEqualTo(Map.of("ok", false, "reason", "not_run"));
        assertThat(latest(token).get("pendingPlan")).isNull();
    }

    @Test
    void a_step_whose_call_fails_stops_the_rest() {
        String token = token("u-" + UUID.randomUUID());
        String sid = start(token);
        String planId = proposeThree(sid, token, "throw");

        llm.say("Detuve la 12. El aviso falló.");
        List<Sse> done = send(sid, token,
                Map.of("text", "", "selection", Map.of("kind", "confirm", "callId", planId)));

        assertThat(tool.ran()).containsExactly("12", "Cuadre");
        assertThat(states(done)).containsExactly("1:running", "1:done", "2:running", "2:failed", "3:skipped");
        assertThat(resultOf(done, "s2")).containsEntry("status", "FAILED");
        assertEveryCallHasOneResult(llm.lastRequest());
        assertThat(resultsIn(llm.lastRequest()).get(1).isError()).isTrue();
        assertThat(resultsIn(llm.lastRequest()).get(2).value()).isEqualTo(Map.of("ok", false, "reason", "not_run"));
    }

    @Test
    void a_step_that_asks_for_a_code_fails_and_no_prompt_is_raised() {
        String token = token("u-" + UUID.randomUUID());
        String sid = start(token);

        llm.round(answer(call("k1", "plan_coded", Map.of("what", "boleto", "how", "code")), stop("k2", "12")));
        List<Sse> proposed = send(sid, token, Map.of("text", "paga el boleto y para la 12"));
        String planId = (String) payload(proposed.get(0)).get("callId");

        llm.say("No pude pagar el boleto.");
        List<Sse> done = send(sid, token,
                Map.of("text", "", "selection", Map.of("kind", "confirm", "callId", planId)));

        assertThat(states(done)).containsExactly("1:running", "1:failed", "2:skipped");
        assertThat(done).noneMatch(e -> "secret_prompt".equals(e.data().get("kind")));
        assertThat(String.valueOf(resultOf(done, "k1").get("value"))).doesNotContain("_secretPrompt");
        assertThat(llm.lastRequest().history().toString()).doesNotContain("_secretPrompt");
        assertThat(tool.ran()).containsExactly("boleto");
        Map<String, Object> latest = latest(token);
        assertThat(latest.get("pendingSecret")).isNull();
        assertThat(latest.get("pendingPlan")).isNull();
    }

    // ------------------------------------------------------------------
    // Once, whatever happens around it
    // ------------------------------------------------------------------

    @Test
    void two_answers_to_one_plan_run_it_once() throws Exception {
        String token = token("u-" + UUID.randomUUID());
        String sid = start(token);
        String planId = proposeThree(sid, token, null);

        List<List<TurnEvent>> outcomes = twice(sid, planId, "Hecho.");
        assertThat(tool.ran()).containsExactly("12", "Cuadre", "15");
        assertThat(outcomes).filteredOn(o -> o.stream().anyMatch(e -> e instanceof TurnEvent.ToolCall)).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o.stream().noneMatch(e -> e instanceof TurnEvent.ToolCall))
                .singleElement().satisfies(o -> assertThat(o).extracting(e -> e.getClass().getSimpleName())
                        .containsExactly("Token", "End"));
        assertThat(workingMemory.get(new Ids.SessionId(sid))).doesNotContainKey("pending_plan");
    }

    @Test
    void two_confirms_of_one_card_run_the_change_once() throws Exception {
        String token = token("u-" + UUID.randomUUID());
        String sid = start(token);

        llm.tool("c1", "plan_apply", Map.of("what", "Especial 80"));
        String card = (String) payload(send(sid, token, Map.of("text", "aplica Especial 80")).get(1)).get("callId");

        List<List<TurnEvent>> outcomes = twice(sid, card, "Aplicado.");
        assertThat(tool.ran()).containsExactly("Especial 80");
        assertThat(outcomes).filteredOn(o -> o.stream().anyMatch(e -> e instanceof TurnEvent.ToolCall)).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o.stream().noneMatch(e -> e instanceof TurnEvent.ToolCall))
                .singleElement().satisfies(o -> assertThat(((TurnEvent.Token) o.get(0)).text())
                        .startsWith("Esa confirmación ya no está vigente"));
    }

    @Test
    void a_plan_agreed_to_runs_to_its_end_when_nobody_listens_and_a_message_meanwhile_waits() throws Exception {
        String token = token("u-" + UUID.randomUUID());
        String sid = start(token);
        Ids.SessionId id = new Ids.SessionId(sid);
        String planId = proposeThree(sid, token, null);
        int modelCalls = llm.requests().size();

        // The first step is held inside the tool; the caller leaves after the
        // first event of the run.
        tool.hold = new java.util.concurrent.CountDownLatch(1);
        engine.send(id, new UserMessage("", Map.of("_selection", Map.of("kind", "confirm", "callId", planId))),
                appAuth()).take(1).collectList().block(Duration.ofSeconds(10));
        await(() -> tool.ran().size() == 1);

        // While it runs: the frame is not offered again, and a message is told to wait.
        assertThat(latest(token).get("pendingPlan")).isNull();
        List<Sse> meanwhile = send(sid, token, Map.of("text", "¿ya?"));
        assertThat(meanwhile).extracting(Sse::event).containsExactly("token", "end");
        assertThat(meanwhile.get(0).data().get("text"))
                .isEqualTo("Todavía estoy haciendo esos cambios. Dame un momento.");

        tool.hold.countDown();
        tool.hold = null;
        await(() -> tool.ran().size() == 3 && !workingMemory.get(id).containsKey("pending_plan"));
        assertThat(tool.ran()).containsExactly("12", "Cuadre", "15");
        // Nobody was there to read an account: the model was not asked.
        assertThat(llm.requests()).hasSize(modelCalls);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> finished = (List<Map<String, Object>>) history(sid, token).get("lastToolResults");
        assertThat(finished).extracting(r -> r.get("callId")).contains("s1", "s2", "s3");
    }

    @Test
    void a_run_cut_short_by_a_restart_is_told_from_what_was_stored_and_nothing_more_runs() {
        String token = token("u-" + UUID.randomUUID());
        String sid = start(token);
        Ids.SessionId id = new Ids.SessionId(sid);
        proposeThree(sid, token, null);
        int modelCalls = llm.requests().size();
        long counted = usage.usage(tenant, null).messages();

        // What a restart leaves behind: the slot says RUNNING, no instance runs it.
        Map<String, Object> slot = new LinkedHashMap<>(cast((Map<?, ?>) workingMemory.get(id).get("pending_plan")));
        List<Map<String, Object>> steps = new ArrayList<>();
        String[] left = {"done", "running", "pending"};
        for (Object step : (List<?>) slot.get("steps")) {
            Map<String, Object> copy = new LinkedHashMap<>(cast((Map<?, ?>) step));
            copy.put("state", left[steps.size()]);
            steps.add(copy);
        }
        slot.put("state", "RUNNING");
        slot.put("steps", steps);
        workingMemory.merge(id, Map.of("pending_plan", slot));
        assertThat(latest(token).get("pendingPlan")).isNull();

        List<Sse> next = send(sid, token, Map.of("text", "¿qué pasó?"));
        assertThat(next).extracting(Sse::event).containsExactly("token", "end");
        assertThat(next.get(0).data().get("text"))
                .isEqualTo("El plan se interrumpió. Hecho: 1. Sin confirmar: 2. Sin hacer: 3.");
        assertThat(tool.ran()).isEmpty();
        assertThat(llm.requests()).hasSize(modelCalls);
        assertThat(usage.usage(tenant, null).messages()).isEqualTo(counted);
        assertThat(workingMemory.get(id)).doesNotContainKey("pending_plan");

        // The conversation goes on.
        llm.say("Dime.");
        assertThat(send(sid, token, Map.of("text", "hola"))).extracting(Sse::event).containsExactly("token", "end");
    }

    // ------------------------------------------------------------------
    // One frame per session
    // ------------------------------------------------------------------

    @Test
    void a_plan_replaces_a_confirmation_and_a_code_prompt_and_each_of_them_replaces_a_plan() {
        String token = token("u-" + UUID.randomUUID());
        String sid = start(token);
        Ids.SessionId id = new Ids.SessionId(sid);

        // A confirmation waits; a plan takes its place.
        llm.tool("c1", "plan_apply", Map.of("what", "uno"));
        String card = (String) payload(send(sid, token, Map.of("text", "aplica uno")).get(1)).get("callId");
        // (typing another request drops the card; the plan is raised by that request)
        String plan = proposeThree(sid, token, null);
        assertThat(workingMemory.get(id)).containsKey("pending_plan").doesNotContainKey("pending_confirm");
        assertThat(send(sid, token, Map.of("text", "", "selection", Map.of("kind", "confirm", "callId", card)))
                .get(0).data().get("text")).asString().startsWith("Esa confirmación ya no está vigente");
        assertThat(tool.ran()).isEmpty();

        // A confirmation takes the plan's place.
        llm.tool("c2", "plan_apply", Map.of("what", "dos"));
        send(sid, token, Map.of("text", "mejor aplica dos"));
        assertThat(workingMemory.get(id)).containsKey("pending_confirm").doesNotContainKey("pending_plan");
        Map<String, Object> latest = latest(token);
        assertThat(latest.get("pendingPlan")).isNull();
        assertThat(latest.get("pendingConfirm")).isNotNull();
        assertThat(send(sid, token, Map.of("text", "", "selection", Map.of("kind", "confirm", "callId", plan)))
                .get(0).data().get("text")).asString().startsWith("Esa confirmación ya no está vigente");

        // A code prompt waits; a plan takes its place.
        llm.tool("k1", "plan_code", Map.of("what", "pago", "how", "code"));
        send(sid, token, Map.of("text", "paga"));
        assertThat(workingMemory.get(id)).containsKey("pending_secret").doesNotContainKey("pending_confirm");
        proposeThree(sid, token, null);
        assertThat(workingMemory.get(id)).containsKey("pending_plan")
                .doesNotContainKeys("pending_secret", "pending_confirm");

        // A code prompt takes the plan's place.
        llm.tool("k2", "plan_code", Map.of("what", "pago", "how", "code"));
        send(sid, token, Map.of("text", "paga ahora"));
        assertThat(workingMemory.get(id)).containsKey("pending_secret").doesNotContainKey("pending_plan");
        assertThat(tool.ran()).containsExactly("pago", "pago");
    }

    // ------------------------------------------------------------------
    // Channels without cards
    // ------------------------------------------------------------------

    @Test
    void on_a_text_channel_the_plan_is_a_list_answered_with_yes_or_dropped_by_another_request() {
        String token = token("u-" + UUID.randomUUID());
        String sid = start(token);
        Ids.SessionId id = new Ids.SessionId(sid);

        llm.round(answer(stop("s1", "12"), notify("s2", "Cierre", "07")));
        List<TurnEvent> proposed = say(id, "para la 12 y avisa a la 07");
        assertThat(proposed).extracting(e -> e.getClass().getSimpleName()).containsExactly("UiHint", "Token", "End");
        assertThat(((TurnEvent.Token) proposed.get(1)).text()).isEqualTo(
                "Voy a hacer esto, en orden:\n1. Detener la venta en la estación 12\n"
                        + "2. Enviar el aviso «Cierre» a la estación 07\n¿Lo hago?");

        // Another request: the plan is dropped and the request is handled.
        llm.say("Van 1,200 hoy.");
        say(id, "mejor dime las ventas");
        assertThat(workingMemory.get(id)).doesNotContainKey("pending_plan");
        assertThat(tool.ran()).isEmpty();

        // «sí» is all of it.
        llm.round(answer(stop("s3", "12"), notify("s4", "Cierre", "07")));
        say(id, "para la 12 y avisa a la 07");
        llm.say("Hecho.");
        List<TurnEvent> done = say(id, "sí");
        assertThat(tool.ran()).containsExactly("12", "Cierre");
        assertThat(done).filteredOn(e -> e instanceof TurnEvent.ToolResult).hasSize(2);

        // «no» is none of it.
        llm.round(answer(stop("s5", "15"), stop("s6", "16")));
        say(id, "para la 15 y la 16");
        int modelCalls = llm.requests().size();
        List<TurnEvent> none = say(id, "no");
        assertThat(((TurnEvent.Token) none.get(0)).text()).isEqualTo("Listo, no hice nada.");
        assertThat(tool.ran()).hasSize(2);
        assertThat(llm.requests()).hasSize(modelCalls);
    }

    // ------------------------------------------------------------------
    // Review fixes (3R: R17–R19)
    // ------------------------------------------------------------------

    private static final String ASK_TEXT = "No quiero hacer de más. Responde TODO para hacerlos todos, CANCELAR "
            + "para no hacer nada, o dime cuáles pasos quieres (por ejemplo «solo la 1»).";
    private static final String ASK_APP = "No quiero hacer de más. Toca «Hacer todo», o dime cuáles pasos quieres "
            + "(por ejemplo «solo la 1»).";

    /** A plan of three raised on a text channel: stop 12, notify 07 «Cuadre», stop 15. */
    private void proposeThreeTyped(Ids.SessionId id) {
        llm.round(answer(stop("t1", "12"), notify("t2", "Cuadre", "07"), stop("t3", "15")));
        say(id, "para la 12, avisa a la 07 y para la 15");
        assertThat(workingMemory.get(id)).containsKey("pending_plan");
    }

    private static String textOf(List<TurnEvent> turn) {
        return turn.stream().filter(e -> e instanceof TurnEvent.Token)
                .map(e -> ((TurnEvent.Token) e).text()).collect(java.util.stream.Collectors.joining());
    }

    @Test
    void a_typed_yes_with_more_words_or_a_number_never_runs_the_whole_plan() {
        String token = token("u-" + UUID.randomUUID());
        Ids.SessionId id = new Ids.SessionId(start(token));

        // R17: each of these ran all three steps.
        for (String reply : List.of("1", "2", "sí, solo el aviso", "dale, menos el aviso", "ok pero la 15 no",
                "sí, la primera", "solo la 9", "1-3")) {
            tool.reset();
            id = new Ids.SessionId(start(token));
            proposeThreeTyped(id);
            int modelCalls = llm.requests().size();
            List<TurnEvent> asked = say(id, reply);
            assertThat(tool.ran()).as(reply).isEmpty();
            assertThat(llm.requests()).as(reply).hasSize(modelCalls);
            // The plan waits, with its buttons again, and the person is told how to choose.
            assertThat(asked).as(reply).extracting(e -> e.getClass().getSimpleName())
                    .containsExactly("UiHint", "Token", "End");
            assertThat(((TurnEvent.UiHint) asked.get(0)).kind()).isEqualTo("plan");
            assertThat(textOf(asked)).as(reply).isEqualTo(ASK_TEXT);
            assertThat(cast((Map<?, ?>) workingMemory.get(id).get("pending_plan")))
                    .as(reply).containsEntry("state", "PENDING");
        }

        // Asked once: a second answer that still does not choose is a new
        // request, and the plan is dropped.
        llm.say("¿Cuál aviso quieres dejar fuera?");
        List<TurnEvent> again = say(id, "dale, menos el aviso");
        assertThat(textOf(again)).isEqualTo("¿Cuál aviso quieres dejar fuera?");
        assertThat(workingMemory.get(id)).doesNotContainKey("pending_plan");
        assertThat(tool.ran()).isEmpty();
    }

    @Test
    void typed_step_numbers_run_those_steps_and_the_words_of_the_reply_line_are_all_or_nothing() {
        String token = token("u-" + UUID.randomUUID());
        Ids.SessionId first = new Ids.SessionId(start(token));

        Map<String, List<String>> subsets = new LinkedHashMap<>();
        subsets.put("sí, solo la 1", List.of("12"));
        subsets.put("solo la 1 y la 3", List.of("12", "15"));
        subsets.put("3 y 1", List.of("12", "15"));
        subsets.put("Dale, pasos 2 y 3.", List.of("Cuadre", "15"));
        subsets.put("todo menos la 2", List.of("12", "15"));
        subsets.put("sí, menos la 1 y la 2", List.of("15"));
        subsets.put("TODO", List.of("12", "Cuadre", "15"));
        subsets.put("Hacer todo", List.of("12", "Cuadre", "15"));
        subsets.put("sí, dale", List.of("12", "Cuadre", "15"));
        subsets.put("Sí.", List.of("12", "Cuadre", "15"));
        subsets.forEach((reply, expected) -> {
            tool.reset();
            proposeThreeTyped(first);
            llm.say("Hecho.");
            say(first, reply);
            assertThat(tool.ran()).as(reply).containsExactlyElementsOf(expected);
            assertThat(workingMemory.get(first)).as(reply).doesNotContainKey("pending_plan");
        });

        for (String reply : List.of("CANCELAR", "no", "ninguno", "no, déjalo")) {
            tool.reset();
            // A session of its own: a long one also asks the model for its summary.
            Ids.SessionId id = new Ids.SessionId(start(token));
            proposeThreeTyped(id);
            int modelCalls = llm.requests().size();
            assertThat(textOf(say(id, reply))).as(reply).isEqualTo("Listo, no hice nada.");
            assertThat(tool.ran()).as(reply).isEmpty();
            assertThat(llm.requests()).as(reply).hasSize(modelCalls);
            assertThat(workingMemory.get(id)).as(reply).doesNotContainKey("pending_plan");
        }

        // A bare number is a step once the person was asked for step numbers.
        tool.reset();
        Ids.SessionId id = new Ids.SessionId(start(token));
        proposeThreeTyped(id);
        assertThat(textOf(say(id, "2"))).isEqualTo(ASK_TEXT);
        llm.say("Envié el aviso.");
        say(id, "2");
        assertThat(tool.ran()).containsExactly("Cuadre");
    }

    @Test
    void in_the_app_an_unclear_typed_answer_keeps_the_card_and_is_not_counted() {
        String token = token("u-" + UUID.randomUUID());
        String sid = start(token);
        String planId = proposeThree(sid, token, null);
        int modelCalls = llm.requests().size();
        long counted = usage.usage(tenant, null).messages();

        List<Sse> asked = send(sid, token, Map.of("text", "dale, menos el aviso"));
        assertThat(asked).extracting(Sse::event).containsExactly("ui_hint", "token", "end");
        assertThat(asked.get(0).data().get("kind")).isEqualTo("plan");
        assertThat(payload(asked.get(0))).isEqualTo(cast((Map<?, ?>) latest(token).get("pendingPlan")));
        assertThat(payload(asked.get(0))).containsEntry("callId", planId);
        assertThat(asked.get(1).data().get("text")).isEqualTo(ASK_APP);
        assertThat(tool.ran()).isEmpty();
        assertThat(llm.requests()).hasSize(modelCalls);
        assertThat(usage.usage(tenant, null).messages()).isEqualTo(counted);

        // The card still answers.
        llm.say("Detuve la 12.");
        send(sid, token, Map.of("text", "", "selection",
                Map.of("kind", "confirm_items", "callId", planId, "itemIds", List.of("1"))));
        assertThat(tool.ran()).containsExactly("12");
    }

    @Test
    void a_confirm_that_names_steps_runs_only_those_and_an_answer_without_the_plans_id_runs_nothing() {
        String token = token("u-" + UUID.randomUUID());
        String sid = start(token);

        // R18: «confirm» with ids ran every step.
        String first = proposeThree(sid, token, null);
        llm.say("Detuve la 12.");
        List<Sse> some = send(sid, token, Map.of("text", "", "selection",
                Map.of("kind", "confirm", "callId", first, "itemIds", List.of("1"))));
        assertThat(tool.ran()).containsExactly("12");
        assertThat(states(some)).containsExactly("1:running", "1:done", "2:skipped", "3:skipped");

        // Ids that leave nothing to run are a cancel, whatever the kind.
        tool.reset();
        String second = proposeThree(sid, token, null);
        List<Sse> none = send(sid, token, Map.of("text", "", "selection",
                Map.of("kind", "confirm", "callId", second, "itemIds", List.of())));
        assertThat(none.get(0).data().get("text")).isEqualTo("Listo, no hice nada.");
        assertThat(tool.ran()).isEmpty();

        // R18: an answer that does not name the plan matched it.
        String third = proposeThree(sid, token, null);
        int modelCalls = llm.requests().size();
        for (Map<String, Object> selection : List.of(Map.<String, Object>of("kind", "confirm"),
                Map.<String, Object>of("kind", "confirm_items", "itemIds", List.of("1")),
                Map.<String, Object>of("kind", "cancel"))) {
            List<Sse> unnamed = send(sid, token, Map.of("text", "", "selection", selection));
            assertThat(unnamed).extracting(Sse::event).containsExactly("token", "end");
            assertThat(unnamed.get(0).data().get("text")).isEqualTo(
                    "Esa confirmación ya no está vigente. Dime de nuevo qué quieres hacer.");
        }
        assertThat(tool.ran()).isEmpty();
        assertThat(llm.requests()).hasSize(modelCalls);
        assertThat(cast((Map<?, ?>) latest(token).get("pendingPlan"))).containsEntry("callId", third);
    }

    @Test
    void a_single_confirmation_still_takes_an_answer_without_a_call_id() {
        String token = token("u-" + UUID.randomUUID());
        String sid = start(token);

        // The chat app sends the selection without a callId when it has none.
        llm.tool("c1", "plan_apply", Map.of("what", "Especial 80"));
        send(sid, token, Map.of("text", "aplica Especial 80"));
        llm.say("Aplicado.");
        send(sid, token, Map.of("text", "", "selection", Map.of("kind", "confirm")));
        assertThat(tool.ran()).containsExactly("Especial 80");
    }

    @Test
    void a_cancel_at_the_same_moment_as_a_confirm_tells_what_really_happened() throws Exception {
        String token = token("u-" + UUID.randomUUID());
        String sid = start(token);
        Ids.SessionId id = new Ids.SessionId(sid);
        String planId = proposeThree(sid, token, null);

        gate.pairReadsOf(id);
        llm.say("Hecho.");
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        List<TurnEvent> confirmed;
        List<TurnEvent> cancelled;
        try {
            var confirm = pool.submit(() -> engine.send(id, new UserMessage("", Map.of("_selection",
                    Map.of("kind", "confirm", "callId", planId))), appAuth()).collectList().block(Duration.ofSeconds(30)));
            var cancel = pool.submit(() -> engine.send(id, new UserMessage("", Map.of("_selection",
                    Map.of("kind", "cancel", "callId", planId))), appAuth()).collectList().block(Duration.ofSeconds(30)));
            confirmed = confirm.get();
            cancelled = cancel.get();
        } finally {
            gate.open();
            pool.shutdownNow();
        }

        // R19: the plan ran while the cancel answered «Listo, no hice nada.»
        if (tool.ran().isEmpty()) {
            assertThat(textOf(cancelled)).isEqualTo("Listo, no hice nada.");
            assertThat(textOf(confirmed)).startsWith("Esa confirmación ya no está vigente");
        } else {
            assertThat(tool.ran()).containsExactly("12", "Cuadre", "15");
            assertThat(textOf(cancelled)).isEqualTo("Ya estaba en marcha, no alcancé a cancelarlo.");
        }
        assertThat(workingMemory.get(id)).doesNotContainKey("pending_plan");
    }

    @Test
    void a_cancel_that_loses_the_claim_waits_to_learn_who_won() throws Exception {
        String token = token("u-" + UUID.randomUUID());
        String sid = start(token);
        Ids.SessionId id = new Ids.SessionId(sid);

        // A confirm took the claim and has not yet marked the plan as running.
        String planId = proposeThree(sid, token, null);
        assertThat(workingMemory.claim(id, "plan:" + planId)).isTrue();
        java.util.concurrent.ScheduledExecutorService later =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        try {
            later.schedule(() -> workingMemory.merge(id, Map.of("plan_fate",
                    Map.of("callId", planId, "fate", "RUN"))), 120, java.util.concurrent.TimeUnit.MILLISECONDS);
            List<Sse> lost = send(sid, token, Map.of("text", "", "selection", Map.of("kind", "cancel", "callId", planId)));
            assertThat(lost).extracting(Sse::event).containsExactly("token", "end");
            assertThat(lost.get(0).data().get("text")).isEqualTo("Ya estaba en marcha, no alcancé a cancelarlo.");
            // It is the confirm's to finish: the cancel left the slot alone.
            assertThat(workingMemory.get(id)).containsKey("pending_plan");
        } finally {
            later.shutdownNow();
            workingMemory.remove(id, "pending_plan");
        }

        // Another cancel took it: nothing ran, and the card is simply used up.
        String other = proposeThree(sid, token, null);
        assertThat(workingMemory.claim(id, "plan:" + other)).isTrue();
        gate.beforeClaim = (session, claim) -> workingMemory.remove(id, "pending_plan");
        List<Sse> second = send(sid, token, Map.of("text", "", "selection", Map.of("kind", "cancel", "callId", other)));
        assertThat(second.get(0).data().get("text")).isEqualTo(
                "Esa confirmación ya no está vigente. Dime de nuevo qué quieres hacer.");
        assertThat(tool.ran()).isEmpty();
    }

    @Test
    void a_plan_replaced_between_the_read_and_the_claim_does_not_run() {
        String token = token("u-" + UUID.randomUUID());
        String sid = start(token);
        Ids.SessionId id = new Ids.SessionId(sid);
        String planId = proposeThree(sid, token, null);
        int modelCalls = llm.requests().size();

        // Another request replaced the frame after this one read it as pending.
        gate.beforeClaim = (session, claim) -> {
            if (claim.equals("plan:" + planId)) workingMemory.remove(id, "pending_plan");
        };
        List<Sse> late = send(sid, token, Map.of("text", "", "selection", Map.of("kind", "confirm", "callId", planId)));

        assertThat(tool.ran()).isEmpty();
        assertThat(late).extracting(Sse::event).containsExactly("token", "end");
        assertThat(late.get(0).data().get("text")).isEqualTo(
                "Esa confirmación ya no está vigente. Dime de nuevo qué quieres hacer.");
        assertThat(llm.requests()).hasSize(modelCalls);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static LlmClient.LlmEvent.ToolUseProposal stop(String id, String station) {
        return call(id, "plan_stop", Map.of("what", station, "station", station));
    }

    private static LlmClient.LlmEvent.ToolUseProposal notify(String id, String title, String station) {
        return call(id, "plan_notify", Map.of("what", title, "title", title, "station", station));
    }

    /** A plan of three — stop 12, notify 07 «Cuadre», stop 15 — whose second step answers as {@code how} says. */
    private String proposeThree(String sid, String token, String how) {
        Map<String, Object> second = new LinkedHashMap<>(Map.of("what", "Cuadre", "title", "Cuadre", "station", "07"));
        if (how != null) second.put("how", how);
        llm.round(answer(stop("s1", "12"), call("s2", "plan_notify", second), stop("s3", "15")));
        List<Sse> proposed = send(sid, token, Map.of("text", "para la 12, avisa a la 07 y para la 15"));
        return (String) payload(proposed.stream().filter(e -> "plan".equals(e.data().get("kind")))
                .findFirst().orElseThrow()).get("callId");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> planSteps(Map<String, Object> plan) {
        return (List<Map<String, Object>>) plan.get("steps");
    }

    private static List<Object> planStepTools(Map<String, Object> plan) {
        return planSteps(plan).stream().map(s -> s.get("toolId")).toList();
    }

    /** The {@code plan_step} events of a turn as {@code stepId:state}. */
    private static List<String> states(List<Sse> turn) {
        return turn.stream().filter(e -> "plan_step".equals(e.data().get("kind")))
                .map(e -> payload(e).get("stepId") + ":" + payload(e).get("state")).toList();
    }

    /** The results the model is given for the last answer that called tools. */
    private static List<LlmClient.ToolResultSpec> resultsIn(LlmClient.LlmRequest request) {
        List<LlmClient.ToolResultSpec> last = List.of();
        for (LlmClient.HistoryEntry entry : request.history()) {
            if (entry instanceof LlmClient.HistoryEntry.ToolResults r) last = r.results();
        }
        return last;
    }

    private AuthContext appAuth() {
        return new AuthContext(new Ids.TenantId(tenant), Map.of(), Optional.empty()).onChannel(TurnChannel.APP);
    }

    /** One turn straight into the engine, as WhatsApp's worker sends it. */
    private List<TurnEvent> say(Ids.SessionId id, String text) {
        AuthContext auth = new AuthContext(new Ids.TenantId(tenant), Map.of(), Optional.empty());
        return engine.send(id, new UserMessage(text, Map.of()), auth.onChannel(TurnChannel.WHATSAPP))
                .collectList().block(Duration.ofSeconds(20));
    }

    /** The same «confirm» sent twice at the same moment: both read the frame as pending. */
    private List<List<TurnEvent>> twice(String sid, String callId, String account) throws Exception {
        Ids.SessionId id = new Ids.SessionId(sid);
        gate.pairReadsOf(id);
        UserMessage answer = new UserMessage("", Map.of("_selection", Map.of("kind", "confirm", "callId", callId)));
        llm.say(account);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<List<TurnEvent>> send = () ->
                    engine.send(id, answer, appAuth()).collectList().block(Duration.ofSeconds(30));
            var first = pool.submit(send);
            var second = pool.submit(send);
            return List.of(first.get(), second.get());
        } finally {
            gate.open();
            pool.shutdownNow();
        }
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("condition not met in 10 s");
            Thread.sleep(50);
        }
    }

    private static LlmClient.LlmEvent.ToolUseProposal call(String id, String toolId, Map<String, Object> args) {
        return new LlmClient.LlmEvent.ToolUseProposal(id, toolId, args);
    }

    /** One model answer that proposes every call at once. */
    private static Flux<LlmClient.LlmEvent> answer(LlmClient.LlmEvent.ToolUseProposal... calls) {
        List<LlmClient.LlmEvent> out = new ArrayList<>(List.of(calls));
        out.add(new LlmClient.LlmEvent.Done(LlmClient.StopReason.TOOL_USE, ScriptedLlmClient.ROUND_USAGE));
        return Flux.fromIterable(out);
    }

    /** What the provider requires of a request: each tool call answered once, right after it. */
    private static void assertEveryCallHasOneResult(LlmClient.LlmRequest request) {
        List<LlmClient.HistoryEntry> history = request.history();
        for (int i = 0; i < history.size(); i++) {
            if (!(history.get(i) instanceof LlmClient.HistoryEntry.Assistant a) || a.toolCalls().isEmpty()) continue;
            assertThat(i + 1).as("an entry follows the calls of entry %d", i).isLessThan(history.size());
            assertThat(history.get(i + 1)).isInstanceOf(LlmClient.HistoryEntry.ToolResults.class);
            LlmClient.HistoryEntry.ToolResults results = (LlmClient.HistoryEntry.ToolResults) history.get(i + 1);
            assertThat(results.results()).extracting(LlmClient.ToolResultSpec::callId)
                    .containsExactlyElementsOf(a.toolCalls().stream().map(LlmClient.ToolCallSpec::callId).toList());
        }
    }

    private static List<String> callsIn(LlmClient.LlmRequest request) {
        List<String> out = new ArrayList<>();
        for (LlmClient.HistoryEntry entry : request.history()) {
            if (entry instanceof LlmClient.HistoryEntry.Assistant a) {
                a.toolCalls().forEach(c -> out.add(c.toolName()));
            }
        }
        return out;
    }

    private static Map<String, Object> resultOf(List<Sse> turn, String callId) {
        return turn.stream().filter(e -> "tool_result".equals(e.event()) && callId.equals(e.data().get("callId")))
                .findFirst().orElseThrow().data();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    record Sse(String event, Map<String, Object> data) {}

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payload(Sse hint) {
        return (Map<String, Object>) hint.data().get("payload");
    }

    private String start(String token) {
        return (String) read(client.post().uri("/v1/sessions").contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .bodyValue(Map.of("tenant", tenant, "profileId", "plan_owner", "channel", "admin-app"))
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody()).get("sessionId");
    }

    private Map<String, Object> latest(String token) {
        return read(raw("/v1/sessions/latest?tenant=" + tenant + "&profileId=plan_owner", token)
                .getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, Object> history(String sid, String token) {
        return read(raw("/v1/sessions/" + sid + "/history", token).getBytes(StandardCharsets.UTF_8));
    }

    private String raw(String uri, String token) {
        byte[] body = client.get().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();
        return body == null ? "" : new String(body, StandardCharsets.UTF_8);
    }

    private List<Sse> send(String sid, String token, Map<String, Object> body) {
        byte[] raw = client.post().uri("/v1/sessions/" + sid + "/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .bodyValue(body)
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();
        List<Sse> out = new ArrayList<>();
        if (raw == null) return out;
        for (String block : new String(raw, StandardCharsets.UTF_8).split("\n\n")) {
            String event = null;
            StringBuilder data = new StringBuilder();
            for (String line : block.split("\n")) {
                if (line.startsWith("event:")) event = line.substring(6).trim();
                else if (line.startsWith("data:")) data.append(line.substring(5));
            }
            if (event != null) out.add(new Sse(event, read(data.toString().getBytes(StandardCharsets.UTF_8))));
        }
        return out;
    }

    private Map<String, Object> read(byte[] body) {
        try {
            return json.readValue(body, MAP);
        } catch (Exception e) {
            throw new IllegalStateException("not JSON: " + (body == null ? null : new String(body)), e);
        }
    }

    private String token(String subject) {
        try {
            Date now = new Date();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(ISS).subject(subject).claim("org", org).claim("username", "user-" + subject)
                    .issueTime(now).expirationTime(new Date(now.getTime() + 300_000L))
                    .jwtID(UUID.randomUUID().toString()).build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Every tool of the test: it records what it was asked to do and by which
     * engine turn, and answers as its input tells it to.
     */
    public static class StepTool implements ToolHandler<Map<String, Object>, Map<String, Object>> {
        final List<Map<String, Object>> inputs = new CopyOnWriteArrayList<>();
        final List<String> turns = new CopyOnWriteArrayList<>();
        volatile java.util.concurrent.CountDownLatch hold;

        void reset() {
            inputs.clear();
            turns.clear();
            hold = null;
        }

        /** The {@code what} of each call that reached the tool, in order. */
        List<String> ran() {
            return inputs.stream().map(in -> String.valueOf(in.get("what"))).toList();
        }

        @Override
        public Map<String, Object> execute(Map<String, Object> input, ExecutionContext ctx) {
            inputs.add(input);
            turns.add(ctx.turnId().value());
            java.util.concurrent.CountDownLatch waitFor = hold;
            if (waitFor != null) {
                try {
                    waitFor.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            String how = String.valueOf(input.get("how"));
            if ("throw".equals(how)) throw new IllegalStateException("backend down");
            Map<String, Object> out = new LinkedHashMap<>();
            if ("refuse".equals(how)) {
                out.put("ok", false);
                out.put("reason", "not_now");
                out.put("userSafeMessage", "No pude enviarlo ahora.");
            } else if ("code".equals(how)) {
                out.put("ok", false);
                out.put("reason", "verification_code_required");
                out.put("_secretPrompt", Map.of("title", "Código", "message", "Escribe el código.",
                        "format", "digits", "length", 6, "expiresInSeconds", 300, "canResend", false));
            } else {
                out.put("ok", true);
                out.put("summaryLine", "Hecho: " + input.get("what"));
            }
            return out;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        @Primary
        ScriptedLlmClient scriptedLlmClient() {
            return new ScriptedLlmClient();
        }

        @Bean("planStepTool")
        StepTool planStepTool() {
            return new StepTool();
        }

        @Bean
        @Primary
        SecretPromptApiTest.GatedWorkingMemory gatedWorkingMemory(
                com.yayatechandinnovations.yayaagentic.memory.RedisWorkingMemory real) {
            return new SecretPromptApiTest.GatedWorkingMemory(real);
        }
    }
}
