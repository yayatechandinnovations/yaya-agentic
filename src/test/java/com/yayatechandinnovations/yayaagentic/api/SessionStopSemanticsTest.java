package com.yayatechandinnovations.yayaagentic.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.yayatechandinnovations.yayaagentic.auth.jwt.TenantJwtSecretsChangedEvent;
import com.yayatechandinnovations.yayaagentic.auth.secrets.JwtSecretCipher;
import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.core.Turn;
import com.yayatechandinnovations.yayaagentic.engine.bootstrap.M0Catalog;
import com.yayatechandinnovations.yayaagentic.llm.LlmClient;
import com.yayatechandinnovations.yayaagentic.llm.LlmClient.LlmEvent;
import com.yayatechandinnovations.yayaagentic.llm.LlmClient.StopReason;
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
import com.yayatechandinnovations.yayaagentic.testsupport.ScriptedLlmClient;
import com.yayatechandinnovations.yayaagentic.tool.ToolDescriptor;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandler;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandlerRef;
import com.yayatechandinnovations.yayaagentic.tool.ToolPolicy;
import com.yayatechandinnovations.yayaagentic.usage.UsageMeter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * What stopping a turn does. The client's stop is nothing more than closing
 * the event stream, so each case here closes a real HTTP connection mid-turn
 * and then looks at what the server kept. Contract:
 * {@code docs/guides/admin-panel-sessions.md}, "Stopping a turn".
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import({TestcontainersConfiguration.class, SessionStopSemanticsTest.Config.class})
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key=",
        "spring.main.allow-bean-definition-overriding=true",
        "yaya.agentic.secrets.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "yaya.agentic.sessions.messages-per-minute=60"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionStopSemanticsTest {

    private static final String ISS = "stop-test-identity";
    private static final String SECRET = "stop-semantics-test-signing-secret-32-bytes+";
    private static final String PROFILE = "stop_owner";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    @LocalServerPort int port;
    @Autowired WebTestClient client;
    @Autowired ScriptedLlmClient llm;
    @Autowired GateTool gate;
    @Autowired TenantRepository tenants;
    @Autowired TenantJwtSecretRepository jwtSecrets;
    @Autowired JwtSecretCipher cipher;
    @Autowired ApplicationEventPublisher events;
    @Autowired ProfileRegistry profiles;
    @Autowired M0Catalog catalog;
    @Autowired UsageMeter usage;
    @Autowired @Qualifier("postgresConversationRecorder") ConversationRecorder recorder;
    @Autowired ObjectMapper json;

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final String tenant = "stop-" + run;

    @BeforeAll
    void install() {
        tenants.save(new TenantEntity(tenant, "Stop " + run));
        JwtSecretCipher.Encrypted enc = cipher.encrypt(SECRET);
        jwtSecrets.save(new TenantJwtSecretEntity(tenant, ISS, enc.ciphertext(), enc.iv(), "[]"));
        events.publishEvent(new TenantJwtSecretsChangedEvent(tenant));

        String anyObject = "{\"type\":\"object\"}";
        catalog.registerTool(new ToolDescriptor(new Ids.ToolId("stop_read"), anyObject, anyObject,
                PermissionRequirement.none(), new ToolHandlerRef.Bean("stopGateTool"), ToolPolicy.defaults()));
        catalog.registerTool(new ToolDescriptor(new Ids.ToolId("stop_write"), anyObject, anyObject,
                PermissionRequirement.none(), new ToolHandlerRef.Bean("stopGateTool"),
                new ToolPolicy(Duration.ofSeconds(10), 0, false, true, false, "¿Aplicar «{text}»?")));
        catalog.registerCapability(new Capability(new Ids.CapabilityId("stop-cap-" + run), "Stop", "Stop tools",
                "Use the tools.", List.of(new Ids.ToolId("stop_read"), new Ids.ToolId("stop_write")),
                List.of(), PermissionRequirement.none()));
        // Metered: a running turn holds one message of the allowance. Once the
        // turn is over the message is either given back (the model was never
        // asked) or written to the profile's count — which is how these tests
        // see that the server noticed the disconnect.
        profiles.register(new Profile(new Ids.ProfileId(PROFILE, 1), new Ids.TenantId(tenant), "Stop",
                "Hola.", "You are the operations assistant.",
                List.of(new Ids.CapabilityId("stop-cap-" + run)), List.of(), null, "es", Map.of(),
                Map.of(), null, new Profile.Runtime(null, null, true)));
    }

    @BeforeEach
    void reset() {
        llm.reset();
        gate.arm();
    }

    @AfterEach
    void letGo() {
        gate.release.countDown();
    }

    @Test
    void stop_while_the_model_is_writing_runs_nothing_later_and_stores_nothing() {
        String token = token("u-" + UUID.randomUUID());
        String sid = start(token);
        long before = messagesUsed();
        long profileBefore = profileMessages();

        // The model writes, pauses, and would then ask for a tool.
        Sinks.One<Boolean> resume = Sinks.one();
        AtomicBoolean modelCancelled = new AtomicBoolean();
        llm.round(Flux.<LlmEvent>just(new LlmEvent.TokenChunk("Voy a revisar"))
                .concatWith(resume.asMono().thenMany(Flux.<LlmEvent>just(
                        new LlmEvent.ToolUseProposal("r-late", "stop_read", Map.of("text", "x")),
                        new LlmEvent.Done(StopReason.TOOL_USE, ScriptedLlmClient.ROUND_USAGE))))
                .doOnCancel(() -> modelCancelled.set(true)));

        Stream stream = open(sid, token, Map.of("text", "revisa las ventas"));
        await("first token", () -> stream.saw("token"));
        stream.stop();

        await("the model call is cancelled", modelCancelled::get);
        // The model was asked and had started answering: the stop does not give the message back.
        await("the stopped turn is counted", () -> profileMessages() == profileBefore + 1);
        assertThat(messagesUsed()).isEqualTo(before + 1);
        resume.tryEmitValue(true);
        pause(400);

        assertThat(gate.runs).hasValue(0);
        assertThat(recorder.loadTurns(new Ids.SessionId(sid), TurnRange.all())).isEmpty();
        Map<String, Object> history = history(sid, token);
        assertThat((List<?>) history.get("turns")).isEmpty();
        assertThat((List<?>) history.get("lastToolResults")).isEmpty();
        assertThat(history.get("pendingConfirm")).isNull();

        // The conversation goes on as if the stopped message had not been sent.
        llm.say("Hola.");
        assertThat(send(sid, token, Map.of("text", "hola"))).extracting(Sse::event).containsExactly("token", "end");
        assertThat(llm.lastRequest().history()).containsExactly(new LlmClient.HistoryEntry.User("hola"));
    }

    @Test
    void stop_while_a_tool_call_is_in_flight_still_stores_its_result_and_ends_the_turn_there() {
        String token = token("u-" + UUID.randomUUID());
        String sid = start(token);
        long before = messagesUsed();
        long profileBefore = profileMessages();

        llm.tool("r-1", "stop_read", Map.of("text", "ventas")).say("NUNCA SE PIDE");
        Stream stream = open(sid, token, Map.of("text", "revisa las ventas"));
        await("tool call started", () -> gate.started.getCount() == 0);
        stream.stop();
        // The model had already asked for the tool: the stopped turn counts.
        await("the server noticed the disconnect", () -> profileMessages() == profileBefore + 1);
        assertThat(messagesUsed()).isEqualTo(before + 1);

        gate.release.countDown();
        await("the tool result is stored", () -> !outcomes(sid, token).isEmpty());

        assertThat(gate.interrupted).isFalse();
        assertThat(gate.runs).hasValue(1);
        assertThat(outcomes(sid, token)).singleElement().satisfies(o -> {
            assertThat(o.get("callId")).isEqualTo("r-1");
            assertThat(o.get("tool")).isEqualTo("stop_read");
            assertThat(o.get("status")).isEqualTo("OK");
        });
        // The turn stopped there: the model was not asked to continue.
        pause(400);
        assertThat(llm.requests()).hasSize(1);
        assertThat(gate.runs).hasValue(1);

        // The next turn's model sees the stopped message, the call and its result.
        llm.reset();
        llm.say("Ya lo había revisado.");
        send(sid, token, Map.of("text", "¿y entonces?"));
        List<LlmClient.HistoryEntry> seen = llm.lastRequest().history();
        assertThat(seen.get(0)).isEqualTo(new LlmClient.HistoryEntry.User("revisa las ventas"));
        assertThat(((LlmClient.HistoryEntry.Assistant) seen.get(1)).toolCalls())
                .extracting(LlmClient.ToolCallSpec::callId).containsExactly("r-1");
        assertThat(((LlmClient.HistoryEntry.ToolResults) seen.get(2)).results())
                .singleElement().satisfies(r -> assertThat(r.isError()).isFalse());
        assertThat(seen.get(seen.size() - 1)).isEqualTo(new LlmClient.HistoryEntry.User("¿y entonces?"));
    }

    @Test
    void stop_after_confirming_a_change_still_stores_its_result_under_the_same_call_id() {
        String token = token("u-" + UUID.randomUUID());
        String sid = start(token);

        llm.tool("w-1", "stop_write", Map.of("text", "cerrar"));
        List<Sse> proposed = send(sid, token, Map.of("text", "cierra la venta"));
        String callId = (String) payload(proposed.get(1)).get("callId");
        assertThat(gate.runs).hasValue(0);
        long before = messagesUsed();
        int modelCalls = llm.requests().size();

        Stream stream = open(sid, token,
                Map.of("text", "", "selection", Map.of("kind", "confirm", "callId", callId)));
        await("the confirmed call started", () -> gate.started.getCount() == 0);
        stream.stop();
        await("the server noticed the disconnect", () -> messagesUsed() == before);
        // Still running: nothing is stored yet, and the card is gone.
        assertThat(outcomes(sid, token)).isEmpty();
        assertThat(history(sid, token).get("pendingConfirm")).isNull();

        gate.release.countDown();
        await("the change's result is stored", () -> !outcomes(sid, token).isEmpty());

        assertThat(gate.interrupted).isFalse();
        assertThat(gate.runs).hasValue(1);
        assertThat(outcomes(sid, token)).singleElement().satisfies(o -> {
            assertThat(o.get("callId")).isEqualTo(callId);
            assertThat(o.get("tool")).isEqualTo("stop_write");
            assertThat(o.get("status")).isEqualTo("OK");
        });
        List<Turn> stored = recorder.loadTurns(new Ids.SessionId(sid), TurnRange.all());
        Turn step = stored.get(stored.size() - 1);
        assertThat(step.toolCalls()).singleElement().satisfies(c -> {
            assertThat(c.callId()).isEqualTo(callId);
            assertThat(c.args()).isEqualTo(Map.of("text", "cerrar"));
        });
        assertThat(step.toolResults()).singleElement()
                .satisfies(r -> assertThat(r.value()).isEqualTo(Map.of("applied", "cerrar")));
        // The model was not asked to narrate a turn nobody is listening to.
        pause(400);
        assertThat(llm.requests()).hasSize(modelCalls);

        // The same button again does not run the change a second time.
        List<Sse> again = send(sid, token,
                Map.of("text", "", "selection", Map.of("kind", "confirm", "callId", callId)));
        assertThat(again).extracting(Sse::event).containsExactly("token", "end");
        assertThat(gate.runs).hasValue(1);

        // And the next turn's model knows the change was made.
        llm.reset();
        llm.say("Sí, ya se aplicó.");
        send(sid, token, Map.of("text", "¿se aplicó?"));
        List<LlmClient.HistoryEntry> seen = llm.lastRequest().history();
        assertThat(seen.stream().filter(e -> e instanceof LlmClient.HistoryEntry.ToolResults)
                .flatMap(e -> ((LlmClient.HistoryEntry.ToolResults) e).results().stream()))
                .anySatisfy(r -> {
                    assertThat(r.callId()).isEqualTo(callId);
                    assertThat(r.isError()).isFalse();
                });
        assertThat(gate.runs).hasValue(1);
    }

    @Test
    void stop_while_the_model_reports_a_finished_change_keeps_the_result() {
        String token = token("u-" + UUID.randomUUID());
        String sid = start(token);
        llm.tool("w-2", "stop_write", Map.of("text", "abrir"));
        String callId = (String) payload(send(sid, token, Map.of("text", "abre la venta")).get(1)).get("callId");
        gate.release.countDown();

        AtomicBoolean modelCancelled = new AtomicBoolean();
        llm.round(Flux.<LlmEvent>just(new LlmEvent.TokenChunk("Listo, ")).concatWith(Flux.never())
                .doOnCancel(() -> modelCancelled.set(true)));
        Stream stream = open(sid, token,
                Map.of("text", "", "selection", Map.of("kind", "confirm", "callId", callId)));
        await("the model started its account", () -> stream.saw("token"));
        assertThat(stream.saw("tool_result")).isTrue();
        stream.stop();
        await("the model call is cancelled", modelCancelled::get);

        assertThat(gate.runs).hasValue(1);
        assertThat(outcomes(sid, token)).singleElement().satisfies(o -> {
            assertThat(o.get("callId")).isEqualTo(callId);
            assertThat(o.get("status")).isEqualTo("OK");
        });
    }

    // ------------------------------------------------------------------

    record Sse(String event, Map<String, Object> data) {}

    /** An open event stream the test can close, like the panel's stop button. */
    private static final class Stream {
        final List<String> events = new CopyOnWriteArrayList<>();
        Disposable connection;

        boolean saw(String event) {
            return events.contains(event);
        }

        void stop() {
            connection.dispose();
        }
    }

    private Stream open(String sessionId, String token, Map<String, Object> body) {
        Stream stream = new Stream();
        stream.connection = WebClient.create("http://localhost:" + port).post()
                .uri("/v1/sessions/{id}/messages", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .subscribe(e -> stream.events.add(e.event()), e -> stream.events.add("!" + e));
        return stream;
    }

    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("timed out waiting for: " + what);
            pause(25);
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private long messagesUsed() {
        return usage.usage(tenant, null).messages();
    }

    /** Turns settled as counted; a turn still running, or given back, is not in here. */
    private long profileMessages() {
        return usage.usageByProfile(tenant, usage.usage(tenant, null).month()).stream()
                .filter(p -> p.profileId().equals(PROFILE)).mapToLong(UsageMeter.ProfileTotals::messages).sum();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> outcomes(String sid, String token) {
        return (List<Map<String, Object>>) history(sid, token).get("lastToolResults");
    }

    private Map<String, Object> history(String sid, String token) {
        return read(client.get().uri("/v1/sessions/{id}/history", sid)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payload(Sse hint) {
        return (Map<String, Object>) hint.data().get("payload");
    }

    private String start(String token) {
        return (String) read(client.post().uri("/v1/sessions").contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .bodyValue(Map.of("tenant", tenant, "profileId", PROFILE, "channel", "admin-app"))
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody()).get("sessionId");
    }

    private List<Sse> send(String sessionId, String token, Map<String, Object> body) {
        byte[] raw = client.post().uri("/v1/sessions/{id}/messages", sessionId)
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

    private static String token(String subject) {
        try {
            Date now = new Date();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(ISS).subject(subject)
                    .issueTime(now).expirationTime(new Date(now.getTime() + 300_000L))
                    .jwtID(UUID.randomUUID().toString()).build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A tool call that stays in flight until the test lets it finish. */
    public static class GateTool implements ToolHandler<Map<String, Object>, Map<String, Object>> {
        final AtomicInteger runs = new AtomicInteger();
        volatile boolean interrupted;
        volatile CountDownLatch started = new CountDownLatch(1);
        volatile CountDownLatch release = new CountDownLatch(1);

        void arm() {
            runs.set(0);
            interrupted = false;
            started = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }

        @Override
        public Map<String, Object> execute(Map<String, Object> input, ExecutionContext ctx) {
            runs.incrementAndGet();
            started.countDown();
            try {
                if (!release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("never released");
            } catch (InterruptedException e) {
                interrupted = true;
                throw new IllegalStateException("interrupted mid-call");
            }
            return Map.of("applied", String.valueOf(input.get("text")));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        @Primary
        ScriptedLlmClient scriptedLlmClient() {
            return new ScriptedLlmClient();
        }

        @Bean("stopGateTool")
        GateTool stopGateTool() {
            return new GateTool();
        }
    }
}
