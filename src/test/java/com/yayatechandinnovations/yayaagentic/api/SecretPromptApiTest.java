package com.yayatechandinnovations.yayaagentic.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import com.yayatechandinnovations.yayaagentic.persistence.SessionRepository;
import com.yayatechandinnovations.yayaagentic.persistence.TenantEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantJwtSecretEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantJwtSecretRepository;
import com.yayatechandinnovations.yayaagentic.persistence.TenantRepository;
import com.yayatechandinnovations.yayaagentic.profile.Capability;
import com.yayatechandinnovations.yayaagentic.profile.Profile;
import com.yayatechandinnovations.yayaagentic.profile.ProfileRegistry;
import com.yayatechandinnovations.yayaagentic.recorder.ConversationRecorder;
import com.yayatechandinnovations.yayaagentic.recorder.TurnRange;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import com.yayatechandinnovations.yayaagentic.tenant.TenantOrigins;
import com.yayatechandinnovations.yayaagentic.testsupport.ScriptedLlmClient;
import com.yayatechandinnovations.yayaagentic.tool.ToolDescriptor;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandler;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandlerRef;
import com.yayatechandinnovations.yayaagentic.tool.ToolPolicy;
import com.yayatechandinnovations.yayaagentic.tool.dispatch.HttpToolDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.LoggerFactory;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * The masked secret prompt, {@code docs/guides/admin-panel-sessions.md} §15: a
 * tool asks for a code, the platform collects it and runs the held call once
 * more — and the value the user typed is found nowhere afterwards: not in an
 * event, the stored conversation, {@code latest}, the inspector, working
 * memory, a log line or a request to the model.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import({TestcontainersConfiguration.class, SecretPromptApiTest.Config.class})
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key=",
        "spring.main.allow-bean-definition-overriding=true",
        "yaya.agentic.secrets.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "yaya.agentic.sessions.messages-per-minute=200",
        "yaya.agentic.sessions.session-starts-per-minute=200"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecretPromptApiTest {

    private static final String ISS = "secret-test-identity";
    private static final String SECRET = "the-install-signing-key-for-the-secret-prompt-test";
    private static final String STAFF = "TestIdentity/staff";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    /** The code the tool accepts, and a wrong one. Neither may be found anywhere afterwards. */
    private static final String CODE = "481516";
    private static final String WRONG = "902718";

    @Autowired WebTestClient client;
    @Autowired ScriptedLlmClient llm;
    @Autowired CodeTool tool;
    @Autowired TenantRepository tenants;
    @Autowired TenantJwtSecretRepository jwtSecrets;
    @Autowired JwtSecretCipher cipher;
    @Autowired ApplicationEventPublisher events;
    @Autowired ProfileRegistry profiles;
    @Autowired M0Catalog catalog;
    @Autowired SessionRepository sessions;
    @Autowired TenantOrigins tenantOrigins;
    @Autowired ConversationEngine engine;
    @Autowired WorkingMemory workingMemory;
    @Autowired ConversationRecorder recorder;
    @Autowired ObjectMapper json;
    @Autowired GatedWorkingMemory gate;

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final String tenant = "secret-" + run;
    private final String org = "org-" + run;

    private ListAppender<ILoggingEvent> logs;
    private Logger rootLogger;
    private Logger appLogger;
    private Level appLevel;
    /** Every response body the client received in this test, as raw text. */
    private final List<String> received = new CopyOnWriteArrayList<>();

    @BeforeAll
    void install() {
        TenantEntity t = new TenantEntity(tenant, "Secret " + tenant);
        t.setHostBaseUrl("https://" + tenant + ".example.com");
        tenants.save(t);
        JwtSecretCipher.Encrypted enc = cipher.encrypt(SECRET);
        TenantJwtSecretEntity trust = new TenantJwtSecretEntity(tenant, ISS, enc.ciphertext(), enc.iv(), "[]");
        trust.setPrincipalKind(STAFF);
        trust.setRequiredClaimsJson("{\"org\":\"" + org + "\"}");
        jwtSecrets.save(trust);
        events.publishEvent(new TenantJwtSecretsChangedEvent(tenant));
        tenantOrigins.evict();

        String schema = "{\"type\":\"object\",\"properties\":{\"what\":{\"type\":\"string\"}}}";
        String any = "{\"type\":\"object\"}";
        // Confirmable and code-protected: the shape of a guarded change.
        catalog.registerTool(new ToolDescriptor(new Ids.ToolId("secret_apply"), schema, any,
                PermissionRequirement.none(), new ToolHandlerRef.Bean("secretCodeTool"),
                new ToolPolicy(Duration.ofSeconds(10), 0, false, true, false, "¿Aplicar «{what}»?",
                        false, false, false, true)));
        // Code-protected only, and runnable from a button.
        catalog.registerTool(new ToolDescriptor(new Ids.ToolId("secret_pay"), schema, any,
                PermissionRequirement.none(), new ToolHandlerRef.Bean("secretCodeTool"),
                new ToolPolicy(Duration.ofSeconds(10), 0, false, false, true, null,
                        false, false, false, true)));
        // Returns the same prompt but is not allowed to ask.
        catalog.registerTool(new ToolDescriptor(new Ids.ToolId("secret_not_allowed"), schema, any,
                PermissionRequirement.none(), new ToolHandlerRef.Bean("secretCodeTool"), ToolPolicy.defaults()));
        catalog.registerCapability(new Capability(new Ids.CapabilityId("secret-cap-" + run), "Secret", "Secret tools",
                "Use the tools.", List.of(new Ids.ToolId("secret_apply"), new Ids.ToolId("secret_pay"),
                        new Ids.ToolId("secret_not_allowed")),
                List.of(), PermissionRequirement.none()));
        profiles.register(new Profile(new Ids.ProfileId("secret_owner", 1), new Ids.TenantId(tenant), "Secret owner",
                "Hola.", "You are the operations assistant.",
                List.of(new Ids.CapabilityId("secret-cap-" + run)), List.of(), null, "es",
                Map.of("principalKinds", List.of(STAFF)), Map.of(), null, null));
    }

    @BeforeEach
    void reset() {
        llm.reset();
        tool.reset();
        received.clear();
        rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        appLogger = (Logger) LoggerFactory.getLogger("com.yayatechandinnovations.yayaagentic");
        appLevel = appLogger.getLevel();
        appLogger.setLevel(Level.DEBUG);
        logs = new ListAppender<>();
        logs.start();
        rootLogger.addAppender(logs);
    }

    @AfterEach
    void detach() {
        rootLogger.detachAppender(logs);
        appLogger.setLevel(appLevel);
    }

    // ------------------------------------------------------------------
    // The app: confirm, prompt, wrong code, resend, right code
    // ------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void a_code_protected_change_is_confirmed_then_asked_for_its_code_and_the_code_is_found_nowhere() {
        String token = token("u-" + UUID.randomUUID());
        String sid = start(token);

        llm.tool("m1", "secret_apply", Map.of("what", "Especial 80"));
        List<Sse> proposed = send(sid, token, Map.of("text", "mueve la 07 al control Especial 80"));
        String confirmCall = (String) payload(proposed.get(1)).get("callId");

        // Confirmed: the call runs, answers that it needs a code, and the turn
        // ends on the prompt. The model is not asked to comment on it.
        int modelCalls = llm.requests().size();
        List<Sse> asked = send(sid, token,
                Map.of("text", "", "selection", Map.of("kind", "confirm", "callId", confirmCall)));
        assertThat(asked).extracting(Sse::event).containsExactly("tool_call", "tool_result", "ui_hint", "end");
        assertThat(llm.requests()).hasSize(modelCalls);
        // The prompt is taken out of the result the client and the model get.
        assertThat((Map<String, Object>) asked.get(1).data().get("value"))
                .containsEntry("reason", "verification_code_required").doesNotContainKey("_secretPrompt");
        assertThat(asked.get(2).data().get("kind")).isEqualTo("secret_prompt");
        Map<String, Object> prompt = payload(asked.get(2));
        assertThat(prompt).containsOnlyKeys("callId", "title", "message", "format", "length",
                "expiresInSeconds", "attemptsLeft", "canResend");
        assertThat(prompt).containsEntry("callId", confirmCall)
                .containsEntry("title", "Código de verificación")
                .containsEntry("format", "digits").containsEntry("length", 6)
                .containsEntry("expiresInSeconds", 300).containsEntry("attemptsLeft", 3)
                .containsEntry("canResend", true);
        assertThat((String) prompt.get("message")).startsWith("Envié un código");

        // Reopening the app finds the prompt, next to (no) pending confirm.
        Map<String, Object> latest = latest(token);
        assertThat(latest.get("pendingConfirm")).isNull();
        Map<String, Object> pending = (Map<String, Object>) latest.get("pendingSecret");
        assertThat(pending).containsEntry("callId", confirmCall).containsEntry("length", 6)
                .containsKey("expiresAt").doesNotContainKeys("args", "toolId");
        assertThat(((Number) pending.get("expiresInSeconds")).intValue()).isBetween(290, 300);
        assertThat(history(sid, token).get("pendingSecret")).isNotNull();
        // The prompt is not replayed as a hint of a stored turn.
        assertThat(String.valueOf(history(sid, token).get("turns"))).doesNotContain("secret_prompt");

        // A wrong code: the same call runs again, fails, and asks again.
        List<Sse> wrong = send(sid, token, secret(confirmCall, WRONG));
        assertThat(wrong).extracting(Sse::event).containsExactly("tool_call", "tool_result", "ui_hint", "end");
        assertThat(wrong.get(0).data().get("args")).isEqualTo(Map.of("what", "Especial 80"));
        assertThat((Map<String, Object>) wrong.get(1).data().get("value"))
                .containsEntry("reason", "verification_code_invalid");
        Map<String, Object> again = payload(wrong.get(2));
        String secondCall = (String) again.get("callId");
        assertThat(secondCall).isEqualTo(confirmCall + "-s");
        assertThat(again).containsEntry("attemptsLeft", 2);
        assertThat(llm.requests()).hasSize(modelCalls);

        // The first card is used up.
        List<Sse> stale = send(sid, token, secret(confirmCall, CODE));
        assertThat(stale).extracting(Sse::event).containsExactly("token", "end");
        assertThat(tool.secrets).doesNotContain(CODE);

        // "Reenviar": an empty value runs the call with no secret.
        List<Sse> resent = send(sid, token, secret(secondCall, ""));
        assertThat(resent).extracting(Sse::event).containsExactly("tool_call", "tool_result", "ui_hint", "end");
        String thirdCall = (String) payload(resent.get(2)).get("callId");

        // The right code: the change is made and the model reports it.
        llm.say("Listo: la estación 07 quedó en Especial 80.");
        List<Sse> applied = send(sid, token, secret(thirdCall, CODE));
        assertThat(applied).extracting(Sse::event).containsExactly("tool_call", "tool_result", "token", "end");
        assertThat((Map<String, Object>) applied.get(1).data().get("value")).containsEntry("applied", "Especial 80");
        assertThat(llm.requests()).hasSize(modelCalls + 1);
        assertThat(latest(token).get("pendingSecret")).isNull();

        // What the tool was given, call by call: nothing, the wrong code, nothing, the code.
        assertThat(tool.secrets).containsExactly(null, WRONG, null, CODE);
        // The arguments were the held ones every time; the code was never one.
        assertThat(tool.inputs).allMatch(in -> in.equals(Map.of("what", "Especial 80")));

        // The same tool called again later gets no secret: it was for one request.
        llm.tool("m2", "secret_apply", Map.of("what", "otra"));
        String nextConfirm = (String) payload(send(sid, token, Map.of("text", "ahora otra")).get(1)).get("callId");
        send(sid, token, Map.of("text", "", "selection", Map.of("kind", "confirm", "callId", nextConfirm)));
        assertThat(tool.secrets).last().isNull();

        // The user's turn is stored as a fixed marker.
        List<Map<String, Object>> turns = (List<Map<String, Object>>) history(sid, token).get("turns");
        assertThat(turns).filteredOn(t -> "user".equals(t.get("role"))).extracting(t -> t.get("text"))
                .contains("(código ingresado)", "(reenviar código)");

        assertValuesAreNowhere(sid, token, CODE, WRONG);
    }

    @Test
    void a_prompt_can_be_cancelled_and_a_typed_request_drops_it() {
        String token = token("u-" + UUID.randomUUID());
        String sid = start(token);

        String callId = askForCode(sid, token);
        int modelCalls = llm.requests().size();
        List<Sse> cancelled = send(sid, token,
                Map.of("text", "", "selection", Map.of("kind", "secret_cancel", "callId", callId)));
        assertThat(cancelled).extracting(Sse::event).containsExactly("token", "end");
        assertThat(cancelled.get(0).data().get("text")).isEqualTo("Listo, no hice nada.");
        assertThat(llm.requests()).hasSize(modelCalls);
        assertThat(latest(token).get("pendingSecret")).isNull();

        // In the app the message box is not the code field: text is a new request.
        String second = askForCode(sid, token);
        llm.say("Claro.");
        List<Sse> typed = send(sid, token, Map.of("text", "mejor dime las ventas de hoy"));
        assertThat(typed).extracting(Sse::event).containsExactly("token", "end");
        assertThat(latest(token).get("pendingSecret")).isNull();
        assertThat(send(sid, token, secret(second, CODE))).extracting(Sse::event).containsExactly("token", "end");
        assertThat(tool.secrets).containsOnlyNulls();
    }

    @Test
    void a_new_confirmation_replaces_the_prompt_and_only_a_secret_input_tool_may_ask() {
        String token = token("u-" + UUID.randomUUID());
        String sid = start(token);

        askForCode(sid, token);
        llm.tool("m3", "secret_apply", Map.of("what", "x"));
        send(sid, token, Map.of("text", "aplica x"));
        Map<String, Object> latest = latest(token);
        assertThat(latest.get("pendingConfirm")).isNotNull();
        assertThat(latest.get("pendingSecret")).isNull();
        send(sid, token, Map.of("text", "", "selection",
                Map.of("kind", "cancel", "callId", ((Map<?, ?>) latest.get("pendingConfirm")).get("callId"))));

        // A tool without secretInput: its prompt is removed and nothing is held.
        llm.tool("m4", "secret_not_allowed", Map.of("what", "y")).say("No pude.");
        List<Sse> turn = send(sid, token, Map.of("text", "haz y"));
        assertThat(turn).extracting(Sse::event).containsExactly("tool_call", "tool_result", "token", "end");
        assertThat(String.valueOf(turn.get(1).data().get("value"))).doesNotContain("_secretPrompt");
        assertThat(llm.lastRequest().history().toString()).doesNotContain("_secretPrompt");
        assertThat(latest(token).get("pendingSecret")).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void a_button_can_raise_the_prompt_and_a_lapsed_prompt_runs_nothing() throws Exception {
        String token = token("u-" + UUID.randomUUID());
        String sid = start(token);

        // An `actions` tap on a code-protected tool ends on the prompt too.
        tool.expiresInSeconds = 1;
        List<Sse> tapped = post("/v1/sessions/" + sid + "/actions", token,
                Map.of("tool", "secret_pay", "args", Map.of("what", "A91-228")));
        assertThat(tapped).extracting(Sse::event).containsExactly("tool_call", "tool_result", "ui_hint", "end");
        String callId = (String) payload(tapped.get(2)).get("callId");
        assertThat(((Map<String, Object>) latest(token).get("pendingSecret"))).containsEntry("callId", callId);

        Thread.sleep(1300);
        assertThat(latest(token).get("pendingSecret")).isNull();
        List<Sse> late = send(sid, token, secret(callId, CODE));
        assertThat(late).extracting(Sse::event).containsExactly("token", "end");
        assertThat(tool.secrets).containsExactly((String) null);
        assertValuesAreNowhere(sid, token, CODE);
    }

    // ------------------------------------------------------------------
    // Restart
    // ------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void a_pending_prompt_survives_a_restart_and_is_answered_after_it() {
        String token = token("u-" + UUID.randomUUID());
        String sid = start(token);
        String callId = askForCode(sid, token);

        // The process forgets the session; working memory (Redis) and the
        // stored conversation are what a restart keeps.
        Ids.SessionId id = new Ids.SessionId(sid);
        Map<String, Object> kept = new LinkedHashMap<>(workingMemory.get(id));
        engine.end(id, null);
        workingMemory.set(id, kept);
        sessions.findById(UUID.fromString(sid)).ifPresent(s -> {
            s.setEndedAt(null);
            sessions.save(s);
        });

        Map<String, Object> pending = (Map<String, Object>) history(sid, token).get("pendingSecret");
        assertThat(pending).containsEntry("callId", callId);

        llm.say("Aplicado.");
        List<Sse> applied = send(sid, token, secret(callId, CODE));
        assertThat(applied).extracting(Sse::event).containsExactly("tool_call", "tool_result", "token", "end");
        assertThat((Map<String, Object>) applied.get(1).data().get("value")).containsEntry("applied", "pago");
        assertThat(tool.secrets).containsExactly(null, CODE);
        assertThat(history(sid, token).get("pendingSecret")).isNull();
        assertValuesAreNowhere(sid, token, CODE);
    }

    // ------------------------------------------------------------------
    // WhatsApp, SMS, chat: the next message is the code
    // ------------------------------------------------------------------

    @Test
    void on_a_text_channel_the_next_message_of_the_right_shape_is_the_code_and_the_model_never_reads_it() {
        String token = token("u-" + UUID.randomUUID());
        String sid = start(token);
        Ids.SessionId id = new Ids.SessionId(sid);

        // The prompt's message is the turn's text on a text channel.
        llm.tool("w1", "secret_pay", Map.of("what", "pago"));
        List<TurnEvent> asked = say(id, "paga el boleto", TurnChannel.WHATSAPP);
        assertThat(asked).extracting(e -> e.getClass().getSimpleName())
                .containsExactly("ToolCall", "ToolResult", "UiHint", "Token", "End");
        assertThat(((TurnEvent.Token) asked.get(3)).text()).startsWith("Envié un código");

        // A message that cannot hold the code: the prompt is dropped and the
        // message goes to the model as it is.
        assertThat(engine.awaitsSecret(id, "mejor lo dejo para luego", TurnChannel.WHATSAPP)).isFalse();
        llm.say("De acuerdo.");
        List<TurnEvent> other = say(id, "mejor lo dejo para luego", TurnChannel.WHATSAPP);
        assertThat(other).extracting(e -> e.getClass().getSimpleName()).containsExactly("Token", "End");
        assertThat(llm.lastRequest().history().toString()).contains("mejor lo dejo para luego");
        assertThat(pendingSecret(id)).isNull();
        assertThat(tool.secrets).containsExactly((String) null);

        // Asked again; this time the reply has the format and the length.
        llm.tool("w2", "secret_pay", Map.of("what", "pago"));
        say(id, "paga el boleto", TurnChannel.SMS);
        String spaced = CODE.substring(0, 3) + " " + CODE.substring(3, 5) + "-" + CODE.substring(5);
        // In the app the same text would be a message, not the code.
        assertThat(engine.awaitsSecret(id, spaced, TurnChannel.APP)).isFalse();
        assertThat(engine.awaitsSecret(id, spaced, TurnChannel.SMS)).isTrue();
        llm.say("Pagado.");
        List<TurnEvent> paid = say(id, spaced, TurnChannel.SMS);
        assertThat(paid).extracting(e -> e.getClass().getSimpleName())
                .containsExactly("ToolCall", "ToolResult", "Token", "End");
        assertThat(tool.secrets).last().isEqualTo(CODE);
        assertThat(pendingSecret(id)).isNull();
        // The model was asked to report the result and saw a marker, not the message.
        assertThat(llm.lastRequest().history().toString()).contains("(código ingresado)");
        assertThat(paid.toString()).doesNotContain(CODE).doesNotContain(spaced);

        // A cancel word ends a prompt without the model.
        llm.tool("w3", "secret_pay", Map.of("what", "pago"));
        say(id, "paga otro", TurnChannel.CHAT);
        int modelCalls = llm.requests().size();
        List<TurnEvent> cancelled = say(id, "cancelar", TurnChannel.CHAT);
        assertThat(((TurnEvent.Token) cancelled.get(0)).text()).isEqualTo("Listo, no hice nada.");
        assertThat(llm.requests()).hasSize(modelCalls);
        assertThat(pendingSecret(id)).isNull();

        assertValuesAreNowhere(sid, token, CODE, spaced);
    }


    // ------------------------------------------------------------------
    // Review 2R: R3, R6, R13, R22
    // ------------------------------------------------------------------

    /** Not the prompt's length, and long enough never to appear by chance in an id. */
    private static final String TOO_LONG = "90271833";

    @Test
    void r3_on_a_text_channel_a_message_that_holds_the_code_never_reaches_the_model() {
        String token = token("u-" + UUID.randomUUID());
        String sid = start(token);
        Ids.SessionId id = new Ids.SessionId(sid);

        // The code inside a sentence.
        llm.tool("r3a", "secret_pay", Map.of("what", "pago"));
        say(id, "paga el boleto", TurnChannel.WHATSAPP);
        String sentence = "mi código es " + CODE;
        assertThat(engine.awaitsSecret(id, sentence, TurnChannel.WHATSAPP)).isTrue();
        llm.say("Pagado.");
        List<TurnEvent> paid = say(id, sentence, TurnChannel.WHATSAPP);
        assertThat(paid).extracting(e -> e.getClass().getSimpleName())
                .containsExactly("ToolCall", "ToolResult", "Token", "End");
        assertThat(tool.secrets).last().isEqualTo(CODE);
        assertThat(llm.lastRequest().history().toString()).contains("(código ingresado)");

        // The code followed by a full stop.
        llm.tool("r3b", "secret_pay", Map.of("what", "pago"));
        say(id, "paga otro", TurnChannel.SMS);
        llm.say("Pagado.");
        say(id, CODE + ".", TurnChannel.SMS);
        assertThat(tool.secrets).last().isEqualTo(CODE);
        assertThat(tool.secrets).hasSize(4);

        // A number of the wrong length: the prompt stays, the model is not asked.
        llm.tool("r3c", "secret_pay", Map.of("what", "pago"));
        say(id, "paga otro más", TurnChannel.WHATSAPP);
        int modelCalls = llm.requests().size();
        for (String notTheCode : List.of(TOO_LONG, "el que me llegó es " + TOO_LONG + " creo", "me llegó 7733")) {
            assertThat(engine.awaitsSecret(id, notTheCode, TurnChannel.WHATSAPP)).as(notTheCode).isTrue();
            List<TurnEvent> held = say(id, notTheCode, TurnChannel.WHATSAPP);
            assertThat(held).as(notTheCode).extracting(e -> e.getClass().getSimpleName())
                    .containsExactly("Token", "End");
            assertThat(((TurnEvent.Token) held.get(0)).text()).contains("6");
            assertThat(pendingSecret(id)).as(notTheCode).isNotNull();
        }
        assertThat(llm.requests()).hasSize(modelCalls);
        assertThat(tool.secrets).hasSize(5);
        assertThat(String.valueOf(recorder.loadTurns(id, TurnRange.all()))).doesNotContain("7733");
        // A message with no digit in it is still a new request, and drops the prompt.
        assertThat(engine.awaitsSecret(id, "mejor dime las ventas", TurnChannel.WHATSAPP)).isFalse();
        // The prompt that stayed is answered by the code alone.
        llm.say("Pagado.");
        say(id, CODE, TurnChannel.WHATSAPP);
        assertThat(tool.secrets).last().isEqualTo(CODE);

        assertValuesAreNowhere(sid, token, CODE, TOO_LONG);
    }

    @Test
    void r3_a_code_sent_just_after_the_prompt_lapsed_is_not_read_by_the_model() throws Exception {
        String token = token("u-" + UUID.randomUUID());
        String sid = start(token);
        Ids.SessionId id = new Ids.SessionId(sid);

        tool.expiresInSeconds = 1;
        llm.tool("r3l", "secret_pay", Map.of("what", "pago"));
        say(id, "paga el boleto", TurnChannel.WHATSAPP);
        Thread.sleep(1300);

        int modelCalls = llm.requests().size();
        assertThat(engine.awaitsSecret(id, CODE, TurnChannel.WHATSAPP)).isTrue();
        List<TurnEvent> late = say(id, CODE, TurnChannel.WHATSAPP);
        assertThat(late).extracting(e -> e.getClass().getSimpleName()).containsExactly("Token", "End");
        assertThat(((TurnEvent.Token) late.get(0)).text()).contains("ya no está vigente");
        assertThat(llm.requests()).hasSize(modelCalls);
        assertThat(tool.secrets).containsExactly((String) null);
        // The app sees no prompt, as before.
        assertThat(latest(token).get("pendingSecret")).isNull();
        // Something that is not a code is an ordinary message again.
        assertThat(engine.awaitsSecret(id, "dime las ventas de la 07", TurnChannel.WHATSAPP)).isFalse();

        assertValuesAreNowhere(sid, token, CODE);
    }

    @Test
    void r6_two_answers_to_one_prompt_run_the_held_call_once() throws Exception {
        String token = token("u-" + UUID.randomUUID());
        String sid = start(token);
        Ids.SessionId id = new Ids.SessionId(sid);
        String callId = askForCode(sid, token);

        // Both answers read the session's state before either changes it.
        gate.pairReadsOf(id);
        UserMessage answer = new UserMessage("", Map.of("_selection",
                Map.of("kind", "secret", "callId", callId, "value", CODE)));
        AuthContext auth = new AuthContext(new Ids.TenantId(tenant), Map.of(), Optional.empty())
                .onChannel(TurnChannel.APP);
        llm.say("Pagado.");
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<List<TurnEvent>> send = () ->
                    engine.send(id, answer, auth).collectList().block(Duration.ofSeconds(30));
            var first = pool.submit(send);
            var second = pool.submit(send);
            List<List<TurnEvent>> outcomes = List.of(first.get(), second.get());

            // One request without the code (the one that asked), one with it. Never two.
            assertThat(tool.secrets).containsExactly(null, CODE);
            assertThat(outcomes).filteredOn(o -> o.stream().anyMatch(e -> e instanceof TurnEvent.ToolCall))
                    .hasSize(1);
            assertThat(outcomes).filteredOn(o -> o.stream().noneMatch(e -> e instanceof TurnEvent.ToolCall))
                    .singleElement().satisfies(o -> assertThat(o).extracting(e -> e.getClass().getSimpleName())
                            .containsExactly("Token", "End"));
        } finally {
            gate.open();
            pool.shutdownNow();
        }
        assertThat(pendingSecret(id)).isNull();
    }

    @Test
    void r13_a_turn_that_ends_before_the_code_step_drops_the_prompt() {
        String token = token("u-" + UUID.randomUUID());
        String sid = start(token);

        String callId = askForCode(sid, token);
        // A tap that is answered before the engine reaches the prompt.
        List<Sse> tapped = send(sid, token,
                Map.of("text", "", "selection", Map.of("kind", "banca", "code", "no-such-banca")));
        assertThat(tapped).extracting(Sse::event).containsExactly("token", "end");
        assertThat(latest(token).get("pendingSecret")).isNull();
        assertThat(send(sid, token, secret(callId, CODE))).extracting(Sse::event).containsExactly("token", "end");
        assertThat(tool.secrets).containsOnlyNulls();
    }

    @Test
    void r13_a_prompt_without_a_length_is_not_answered_by_a_typed_message() {
        String token = token("u-" + UUID.randomUUID());
        String sid = start(token);
        Ids.SessionId id = new Ids.SessionId(sid);

        // Letters and digits, no length: before, any word of 4 to 16 letters was "the code".
        tool.length = null;
        tool.format = "alphanumeric";
        llm.tool("r13a", "secret_pay", Map.of("what", "pago"));
        say(id, "paga el boleto", TurnChannel.WHATSAPP);
        int modelCalls = llm.requests().size();
        List<TurnEvent> word = say(id, "gracias", TurnChannel.WHATSAPP);
        assertThat(word).extracting(e -> e.getClass().getSimpleName()).containsExactly("Token", "End");
        assertThat(tool.secrets).containsExactly((String) null);
        assertThat(llm.requests()).hasSize(modelCalls);
        assertThat(pendingSecret(id)).isNotNull();
        // A cancel word still ends it.
        List<TurnEvent> cancelled = say(id, "cancelar", TurnChannel.WHATSAPP);
        assertThat(((TurnEvent.Token) cancelled.get(0)).text()).isEqualTo("Listo, no hice nada.");
        assertThat(pendingSecret(id)).isNull();

        // Digits, no length: a number is neither the code nor a message for the model.
        tool.format = "digits";
        llm.tool("r13b", "secret_pay", Map.of("what", "pago"));
        say(id, "paga el boleto", TurnChannel.SMS);
        modelCalls = llm.requests().size();
        List<TurnEvent> number = say(id, TOO_LONG, TurnChannel.SMS);
        assertThat(number).extracting(e -> e.getClass().getSimpleName()).containsExactly("Token", "End");
        assertThat(tool.secrets).containsExactly(null, null);
        assertThat(llm.requests()).hasSize(modelCalls);
        assertThat(pendingSecret(id)).isNotNull();
        // The chat app's structured answer still works without a length.
        llm.say("Pagado.");
        List<TurnEvent> answered = engine.send(id, new UserMessage("", Map.of("_selection",
                        Map.of("kind", "secret", "value", CODE))),
                new AuthContext(new Ids.TenantId(tenant), Map.of(), Optional.empty()).onChannel(TurnChannel.CHAT))
                .collectList().block(Duration.ofSeconds(20));
        assertThat(answered).extracting(e -> e.getClass().getSimpleName())
                .containsExactly("ToolCall", "ToolResult", "Token", "End");
        assertThat(tool.secrets).last().isEqualTo(CODE);

        assertValuesAreNowhere(sid, token, CODE, TOO_LONG);
    }

    @Test
    void r22_a_new_code_can_be_asked_for_a_limited_number_of_times_per_prompt() {
        String token = token("u-" + UUID.randomUUID());
        String sid = start(token);

        String callId = askForCode(sid, token);
        Map<String, Object> frame = null;
        for (int i = 1; i <= 3; i++) {
            List<Sse> resent = send(sid, token, secret(callId, ""));
            assertThat(resent).as("resend " + i).extracting(Sse::event)
                    .containsExactly("tool_call", "tool_result", "ui_hint", "end");
            frame = payload(resent.get(2));
            callId = (String) frame.get("callId");
        }
        assertThat(tool.secrets).hasSize(4).containsOnlyNulls();
        // The prompt that follows the last allowed resend says so.
        assertThat(frame).containsEntry("canResend", false);

        // One more: nothing runs, the prompt stays.
        List<Sse> refused = send(sid, token, secret(callId, ""));
        assertThat(refused).extracting(Sse::event).containsExactly("token", "end");
        assertThat(tool.secrets).hasSize(4);
        assertThat(((Map<?, ?>) latest(token).get("pendingSecret")).get("callId")).isEqualTo(callId);

        // The code is still accepted.
        llm.say("Pagado.");
        List<Sse> applied = send(sid, token, secret(callId, CODE));
        assertThat(applied).extracting(Sse::event).containsExactly("tool_call", "tool_result", "token", "end");
        assertThat(tool.secrets).last().isEqualTo(CODE);
    }

    // ------------------------------------------------------------------

    /** Raises a prompt with the non-confirmable tool and returns its call id. */
    private String askForCode(String sid, String token) {
        llm.tool("p-" + UUID.randomUUID().toString().substring(0, 6), "secret_pay", Map.of("what", "pago"));
        List<Sse> asked = send(sid, token, Map.of("text", "paga"));
        assertThat(asked).extracting(Sse::event).containsExactly("tool_call", "tool_result", "ui_hint", "end");
        return (String) payload(asked.get(2)).get("callId");
    }

    /**
     * The values are in no response the client got, in nothing stored for the
     * session, in no request to the model and in no log line.
     */
    private void assertValuesAreNowhere(String sid, String token, String... values) {
        Ids.SessionId id = new Ids.SessionId(sid);
        Map<String, String> places = new LinkedHashMap<>();
        places.put("events streamed to the client", String.join("\n", received));
        places.put("history", raw("/v1/sessions/" + sid + "/history", token));
        places.put("latest", raw("/v1/sessions/latest?tenant=" + tenant + "&profileId=secret_owner", token));
        places.put("inspector", raw("/v1/sessions/" + sid + "/inspector", token));
        places.put("working memory", String.valueOf(workingMemory.get(id)));
        places.put("stored turns", String.valueOf(recorder.loadTurns(id, TurnRange.all())));
        places.put("requests to the model", String.valueOf(llm.requests()));
        places.put("engine's last prompt", String.valueOf(engine.lastPrompt(id).orElse(null)));
        places.put("engine's tool activity", String.valueOf(engine.lastToolActivity(id).orElse(null)));
        StringBuilder logged = new StringBuilder();
        for (ILoggingEvent e : List.copyOf(logs.list)) {
            logged.append(e.getFormattedMessage()).append('\n');
            if (e.getThrowableProxy() != null) logged.append(e.getThrowableProxy().getMessage()).append('\n');
        }
        places.put("logs", logged.toString());
        assertThat(logged).as("the log capture is live").isNotEmpty();
        places.forEach((place, content) -> {
            for (String value : values) {
                assertThat(content).as(place).doesNotContain(value);
            }
        });
    }

    private Map<String, Object> pendingSecret(Ids.SessionId id) {
        Object pending = workingMemory.get(id).get("pending_secret");
        return pending instanceof Map<?, ?> m && !m.isEmpty() ? cast(m) : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    /** One turn straight into the engine, as a text channel's worker sends it. */
    private List<TurnEvent> say(Ids.SessionId id, String text, String channel) {
        AuthContext auth = new AuthContext(new Ids.TenantId(tenant), Map.of(), Optional.empty());
        return engine.send(id, new UserMessage(text, Map.of()), auth.onChannel(channel))
                .collectList().block(Duration.ofSeconds(20));
    }

    private static Map<String, Object> secret(String callId, String value) {
        return Map.of("text", "", "selection", Map.of("kind", "secret", "callId", callId, "value", value));
    }

    record Sse(String event, Map<String, Object> data) {}

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payload(Sse hint) {
        return (Map<String, Object>) hint.data().get("payload");
    }

    private String start(String token) {
        return (String) read(client.post().uri("/v1/sessions").contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .bodyValue(Map.of("tenant", tenant, "profileId", "secret_owner", "channel", "admin-app"))
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody()).get("sessionId");
    }

    private Map<String, Object> latest(String token) {
        return read(raw("/v1/sessions/latest?tenant=" + tenant + "&profileId=secret_owner", token)
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
        return post("/v1/sessions/" + sid + "/messages", token, body);
    }

    private List<Sse> post(String uri, String token, Map<String, Object> body) {
        byte[] raw = client.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .bodyValue(body)
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();
        List<Sse> out = new ArrayList<>();
        if (raw == null) return out;
        String text = new String(raw, StandardCharsets.UTF_8);
        received.add(text);
        for (String block : text.split("\n\n")) {
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
     * A code-protected tool: without the code it asks for one, with a wrong
     * one it asks again, with the right one it does the work. It records the
     * secret each call was given.
     */
    public static class CodeTool implements ToolHandler<Map<String, Object>, Map<String, Object>> {
        final List<String> secrets = new CopyOnWriteArrayList<>() {
            @Override
            public String toString() {
                return "[" + size() + " recorded]";
            }
        };
        final List<Map<String, Object>> inputs = new CopyOnWriteArrayList<>();
        volatile int expiresInSeconds = 300;
        volatile Integer length = 6;
        volatile String format = "digits";

        void reset() {
            secrets.clear();
            inputs.clear();
            expiresInSeconds = 300;
            length = 6;
            format = "digits";
        }

        @Override
        public Map<String, Object> execute(Map<String, Object> input, ExecutionContext ctx) {
            Object given = ctx.attributes().get(HttpToolDispatcher.SECRET_ATTRIBUTE);
            String secret = given == null ? null : given.toString();
            // CopyOnWriteArrayList takes nulls: "no secret" is recorded as such.
            secrets.add(secret);
            inputs.add(input);
            if (CODE.equals(secret)) {
                return Map.of("ok", true, "applied", String.valueOf(input.get("what")));
            }
            Map<String, Object> prompt = new LinkedHashMap<>();
            prompt.put("title", "Código de verificación");
            prompt.put("message", secret == null
                    ? "Envié un código por SMS al ***1234. Escríbelo para aplicar."
                    : "Envié un código por SMS al ***1234. Ese código no es correcto.");
            prompt.put("format", format);
            if (length != null) prompt.put("length", length);
            prompt.put("expiresInSeconds", expiresInSeconds);
            prompt.put("attemptsLeft", secret == null ? 3 : 2);
            prompt.put("canResend", true);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("reason", secret == null ? "verification_code_required" : "verification_code_invalid");
            out.put("_secretPrompt", prompt);
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

        @Bean("secretCodeTool")
        CodeTool secretCodeTool() {
            return new CodeTool();
        }

        @Bean
        @Primary
        GatedWorkingMemory gatedWorkingMemory(
                com.yayatechandinnovations.yayaagentic.memory.RedisWorkingMemory real) {
            return new GatedWorkingMemory(real);
        }
    }

    /**
     * The real working memory, able to make two turns of one session read its
     * state in step: each read waits for the other turn's same read, so both
     * see the state as it was before either of them changed it.
     */
    public static class GatedWorkingMemory implements WorkingMemory {
        private final WorkingMemory real;
        private volatile String pairedSession;
        private volatile java.util.concurrent.CyclicBarrier barrier;

        GatedWorkingMemory(WorkingMemory real) {
            this.real = real;
        }

        void pairReadsOf(Ids.SessionId id) {
            barrier = new java.util.concurrent.CyclicBarrier(2);
            pairedSession = id.value();
        }

        void open() {
            pairedSession = null;
            beforeClaim = null;
        }

        @Override
        public Map<String, Object> get(Ids.SessionId sessionId) {
            java.util.concurrent.CyclicBarrier b = barrier;
            if (b != null && sessionId.value().equals(pairedSession)) {
                try {
                    b.await(2, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception alone) {
                    // The other turn took a different path: stop pairing.
                    pairedSession = null;
                }
            }
            return real.get(sessionId);
        }

        @Override public void set(Ids.SessionId sessionId, Map<String, Object> values) { real.set(sessionId, values); }
        @Override public void merge(Ids.SessionId sessionId, Map<String, Object> values) { real.merge(sessionId, values); }
        @Override public void clear(Ids.SessionId sessionId) { real.clear(sessionId); }
        @Override public void remove(Ids.SessionId sessionId, String... keys) { real.remove(sessionId, keys); }
        /** Runs just before a claim reaches the store: what another request did in between. */
        volatile java.util.function.BiConsumer<Ids.SessionId, String> beforeClaim;

        @Override
        public boolean claim(Ids.SessionId sessionId, String token) {
            java.util.function.BiConsumer<Ids.SessionId, String> hook = beforeClaim;
            if (hook != null) hook.accept(sessionId, token);
            return real.claim(sessionId, token);
        }
    }
}
