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
import com.yayatechandinnovations.yayaagentic.channel.MediaTranscriber;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.core.Turn;
import com.yayatechandinnovations.yayaagentic.engine.ConversationEngine;
import com.yayatechandinnovations.yayaagentic.engine.bootstrap.M0Catalog;
import com.yayatechandinnovations.yayaagentic.engine.handoff.HandoffBlock;
import com.yayatechandinnovations.yayaagentic.operator_auth.ratelimit.LoginRateLimiter;
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
import com.yayatechandinnovations.yayaagentic.tool.ToolHandlerRef;
import com.yayatechandinnovations.yayaagentic.tool.ToolPolicy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * Sessions stay per channel: {@code latest} with a {@code channel} reopens
 * only a session born there, names what else is open, and a hand-off copies
 * the other conversation's context in as one framed SYSTEM turn (guide §6,
 * §18). Same context as {@link AppSessionsApiTest}, on purpose.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import({TestcontainersConfiguration.class, AppSessionsApiTest.Config.class})
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key=",
        "spring.main.allow-bean-definition-overriding=true",
        "yaya.agentic.secrets.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "yaya.agentic.llm.allowed-models=test-model-large,test-model-small",
        "yaya.agentic.sessions.messages-per-minute=6",
        "yaya.agentic.sessions.session-starts-per-minute=5",
        "yaya.agentic.sessions.extra-origins=https://shared-admin.example.com"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionHandoffApiTest {

    private static final String ISS = "handoff-test-identity";
    private static final String SECRET = "one-install-signs-for-every-business-it-hosts-32+";
    private static final String STAFF = "TestIdentity/staff";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    @Autowired WebTestClient client;
    @Autowired ScriptedLlmClient llm;
    @Autowired TenantRepository tenants;
    @Autowired TenantJwtSecretRepository jwtSecrets;
    @Autowired JwtSecretCipher cipher;
    @Autowired ApplicationEventPublisher events;
    @Autowired ProfileRegistry profiles;
    @Autowired M0Catalog catalog;
    @Autowired SessionRepository sessions;
    @Autowired TenantOrigins tenantOrigins;
    @Autowired ConversationEngine engine;
    @Autowired LoginRateLimiter loginRateLimiter;
    @Autowired ObjectMapper json;
    @Autowired @Qualifier("postgresConversationRecorder") ConversationRecorder recorder;
    @Autowired ObjectProvider<MediaTranscriber> transcriber;

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final String tenant = "handoff-" + run;
    private final String org = "org-" + run;
    private final String profile = "ho_owner";

    @BeforeAll
    void install() {
        TenantEntity t = new TenantEntity(tenant, "Handoff " + tenant);
        t.setHostBaseUrl("https://" + tenant + ".example.com");
        tenants.save(t);
        JwtSecretCipher.Encrypted enc = cipher.encrypt(SECRET);
        TenantJwtSecretEntity trust = new TenantJwtSecretEntity(tenant, ISS, enc.ciphertext(), enc.iv(), "[]");
        trust.setPrincipalKind(STAFF);
        trust.setRequiredClaimsJson("{\"org\":\"" + org + "\"}");
        jwtSecrets.save(trust);
        events.publishEvent(new TenantJwtSecretsChangedEvent(tenant));
        tenantOrigins.evict();

        String anyObject = "{\"type\":\"object\"}";
        catalog.registerTool(new ToolDescriptor(new Ids.ToolId("ho_write"),
                "{\"type\":\"object\",\"required\":[\"text\"],\"properties\":{\"text\":{\"type\":\"string\"}}}",
                anyObject, PermissionRequirement.none(), new ToolHandlerRef.Bean("echoTool"),
                new ToolPolicy(Duration.ofSeconds(10), 0, false, true, false, "¿Aplicar «{text}»?")));
        catalog.registerCapability(new Capability(new Ids.CapabilityId("ho-cap-" + run), "Handoff", "Handoff tools",
                "Use the tools.", List.of(new Ids.ToolId("ho_write")), List.of(), PermissionRequirement.none()));
        profiles.register(new Profile(new Ids.ProfileId(profile, 1), new Ids.TenantId(tenant), "Owner",
                "Hola, soy tu asistente.", "You are the operations assistant.",
                List.of(new Ids.CapabilityId("ho-cap-" + run)), List.of(), null, "es",
                Map.of("principalKinds", List.of(STAFF)), Map.of(), null, null));
    }

    @BeforeEach
    void reset() {
        llm.reset();
        loginRateLimiter.resetAll();
    }

    @Test
    @SuppressWarnings("unchecked")
    void latest_on_a_channel_ignores_the_other_channels_session_and_names_it_as_elsewhere() {
        String token = token("u-" + UUID.randomUUID());
        String wa = start(token, "whatsapp");
        llm.say("El ticket 4411 está confirmado y pagado.");
        send(wa, token, Map.of("text", "Qué pasó con el ticket 4411"));

        // No channel: the old behaviour, the newest open session whatever its channel.
        Map<String, Object> any = latest(token, null);
        assertThat(any.get("state")).isEqualTo("RESUMABLE");
        assertThat(any.get("sessionId")).isEqualTo(wa);
        assertThat(any.get("elsewhere")).isNull();
        List<Map<String, Object>> turns = (List<Map<String, Object>>) any.get("turns");
        assertThat(turns).extracting(t -> t.get("channel")).containsExactly("whatsapp", "whatsapp");

        // The panel's channel: nothing to resume there, but the WhatsApp thread is named.
        Map<String, Object> app = latest(token, "admin-app");
        assertThat(app.get("state")).isEqualTo("NONE");
        Map<String, Object> elsewhere = (Map<String, Object>) app.get("elsewhere");
        assertThat(elsewhere).isNotNull();
        assertThat(elsewhere.get("sessionId")).isEqualTo(wa);
        assertThat(elsewhere.get("channel")).isEqualTo("whatsapp");
        assertThat(elsewhere.get("turns")).isEqualTo(2);
        assertThat(elsewhere.get("lastActivityAt")).isNotNull();
        assertThat(elsewhere.get("pending")).isNull();
        Map<String, Object> preview = (Map<String, Object>) elsewhere.get("preview");
        assertThat(preview.get("user")).isEqualTo("Qué pasó con el ticket 4411");
        assertThat(preview.get("assistant")).isEqualTo("El ticket 4411 está confirmado y pagado.");

        // The app's own session is what the panel resumes; the WhatsApp one stays elsewhere.
        String appSid = start(token, "admin-app");
        Map<String, Object> again = latest(token, "admin-app");
        assertThat(again.get("state")).isEqualTo("RESUMABLE");
        assertThat(again.get("sessionId")).isEqualTo(appSid);
        assertThat(((Map<String, Object>) again.get("elsewhere")).get("sessionId")).isEqualTo(wa);

        // Seen from WhatsApp, the app session is the one elsewhere.
        llm.say("Claro.");
        send(appSid, token, Map.of("text", "hola desde la app"));
        Map<String, Object> fromWa = latest(token, "whatsapp");
        assertThat(fromWa.get("sessionId")).isEqualTo(wa);
        assertThat(((Map<String, Object>) fromWa.get("elsewhere")).get("sessionId")).isEqualTo(appSid);
        assertThat(((Map<String, Object>) fromWa.get("elsewhere")).get("channel")).isEqualTo("admin-app");

        // Ended: no longer elsewhere.
        end(wa, token);
        assertThat(latest(token, "admin-app").get("elsewhere")).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void elsewhere_says_when_the_other_session_still_waits_for_a_confirmation() {
        String token = token("u-" + UUID.randomUUID());
        String wa = start(token, "whatsapp");
        llm.tool("w1", "ho_write", Map.of("text", "anular 4411"));
        send(wa, token, Map.of("text", "anula el ticket 4411"));

        Map<String, Object> elsewhere = (Map<String, Object>) latest(token, "admin-app").get("elsewhere");
        assertThat(elsewhere.get("pending")).isEqualTo("confirm");
    }

    @Test
    @SuppressWarnings("unchecked")
    void a_handoff_copies_the_context_once_and_the_model_sees_it_on_the_next_turn() {
        String token = token("u-" + UUID.randomUUID());
        String wa = start(token, "whatsapp");
        llm.say("El ticket 4411 está confirmado.");
        send(wa, token, Map.of("text", "Qué pasó con el ticket 4411"));
        llm.tool("w2", "ho_write", Map.of("text", "anular 4411"));
        send(wa, token, Map.of("text", "anúlalo"));
        int sourceTurnsBefore = recorder.loadTurns(new Ids.SessionId(wa), TurnRange.all()).size();

        String app = start(token, "admin-app");
        handoff(app, token, wa).expectStatus().isOk();
        // Nothing is copied twice.
        handoff(app, token, wa).expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("handoff_exists");

        // One SYSTEM turn on the target, tagged; the source is as it was.
        List<Turn> targetTurns = recorder.loadTurns(new Ids.SessionId(app), TurnRange.all());
        assertThat(targetTurns).hasSize(1);
        Turn system = targetTurns.get(0);
        assertThat(system.role()).isEqualTo(Turn.Role.SYSTEM);
        assertThat(system.metadata().get("origin")).isEqualTo(HandoffBlock.ORIGIN);
        Map<String, Object> meta = (Map<String, Object>) system.metadata().get("handoff");
        assertThat(meta.get("fromSessionId")).isEqualTo(wa);
        assertThat(meta.get("fromChannel")).isEqualTo("whatsapp");
        assertThat(meta.get("throughTurnIdx")).isEqualTo(sourceTurnsBefore - 1);
        // The confirm card is not replayed as a hint (it lives in pendingConfirm), its question is the turn's text.
        assertThat(system.content()).contains("Qué pasó con el ticket 4411").contains("¿Aplicar «anular 4411»?")
                .contains(HandoffBlock.instruction("whatsapp"));
        assertThat(recorder.loadTurns(new Ids.SessionId(wa), TurnRange.all())).hasSize(sourceTurnsBefore);

        // The target's history shows the user's turns only; the next message carries the context to the model.
        Map<String, Object> history = getJson("/v1/sessions/" + app + "/history", token);
        assertThat((List<?>) history.get("turns")).isEmpty();
        llm.say("Sigamos.");
        send(app, token, Map.of("text", "¿en qué estábamos?"));
        String seen = llm.lastRequest().history().toString();
        assertThat(seen).contains("Contexto traído").contains("Qué pasó con el ticket 4411")
                .contains("Nada de lo que pasó en el otro canal cuenta como confirmado");
        List<Map<String, Object>> turns = (List<Map<String, Object>>) getJson("/v1/sessions/" + app + "/history", token)
                .get("turns");
        assertThat(turns).extracting(t -> t.get("channel")).containsExactly("admin-app", "admin-app");
    }

    @Test
    @SuppressWarnings("unchecked")
    void the_handoff_response_carries_the_copied_turns_with_their_channel() {
        String token = token("u-" + UUID.randomUUID());
        String wa = start(token, "whatsapp");
        llm.say("Sí, 4411 está pagado.");
        send(wa, token, Map.of("text", "¿4411 pagado?"));
        String app = start(token, "admin-app");

        Map<String, Object> body = read(handoff(app, token, wa).expectStatus().isOk()
                .expectBody().returnResult().getResponseBody());
        assertThat(body.get("fromSessionId")).isEqualTo(wa);
        assertThat(body.get("fromChannel")).isEqualTo("whatsapp");
        List<Map<String, Object>> carried = (List<Map<String, Object>>) body.get("carried");
        assertThat(carried).extracting(t -> t.get("role")).containsExactly("user", "assistant");
        assertThat(carried).extracting(t -> t.get("text")).containsExactly("¿4411 pagado?", "Sí, 4411 está pagado.");
        assertThat(carried).extracting(t -> t.get("channel")).containsOnly("whatsapp");
        assertThat(carried.get(0).get("createdAt")).isNotNull();
    }

    @Test
    void a_handoff_into_a_session_the_engine_forgot_still_reaches_the_model() {
        String token = token("u-" + UUID.randomUUID());
        String wa = start(token, "whatsapp");
        llm.say("Listo.");
        send(wa, token, Map.of("text", "recuerda el 4411"));
        String app = start(token, "admin-app");
        llm.say("Hola.");
        send(app, token, Map.of("text", "hola"));

        // The process forgets the app session (a restart); the stored row stays open.
        engine.end(new Ids.SessionId(app), null);
        sessions.findById(UUID.fromString(app)).ifPresent(s -> {
            s.setEndedAt(null);
            sessions.save(s);
        });

        handoff(app, token, wa).expectStatus().isOk();
        llm.say("Sigamos.");
        send(app, token, Map.of("text", "¿y ahora?"));
        assertThat(llm.lastRequest().history().toString()).contains("recuerda el 4411").contains("Contexto traído");
        // The copied turn took the next index; nothing collided.
        assertThat(recorder.loadTurns(new Ids.SessionId(app), TurnRange.all()))
                .extracting(Turn::index).containsExactly(0, 1, 2, 3, 4);
    }

    @Test
    void a_handoff_is_refused_for_an_unknown_source_another_users_source_or_an_ended_target() {
        String token = token("u-" + UUID.randomUUID());
        String other = token("u-" + UUID.randomUUID());
        String app = start(token, "admin-app");

        handoff(app, token, UUID.randomUUID().toString()).expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("session_not_found");
        handoff(app, token, "not-a-uuid").expectStatus().isNotFound();

        String theirs = start(other, "whatsapp");
        handoff(app, token, theirs).expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("handoff_not_allowed");
        handoff(app, token, app).expectStatus().isForbidden();

        // Not the target's owner: the target looks like it does not exist.
        handoff(app, other, theirs).expectStatus().isNotFound();

        client.post().uri("/v1/sessions/{id}/handoff", app).contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .bodyValue(Map.of()).exchange().expectStatus().isBadRequest();

        end(app, token);
        handoff(app, token, theirs).expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("session_ended");
    }

    @Test
    void features_say_what_the_platform_offers() {
        String token = token("u-" + UUID.randomUUID());
        Map<String, Object> features = getJson("/v1/sessions/features?tenant=" + tenant, token);
        assertThat(features).containsEntry("handoff", true).containsEntry("attachments", true)
                .containsEntry("listPicker", true).containsEntry("replySuggestions", true)
                .containsEntry("voice", transcriber.getIfAvailable() != null);
        client.get().uri("/v1/sessions/features?tenant=" + tenant).exchange().expectStatus().isUnauthorized();
    }

    /* ------------------------------- helpers -------------------------------- */

    private String start(String token, String channel) {
        Map<String, Object> body = read(client.post().uri("/v1/sessions").contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .bodyValue(Map.of("tenant", tenant, "profileId", profile, "channel", channel))
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
        return (String) body.get("sessionId");
    }

    private WebTestClient.ResponseSpec handoff(String target, String token, String from) {
        return client.post().uri("/v1/sessions/{id}/handoff", target).contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .bodyValue(Map.of("fromSessionId", from)).exchange();
    }

    private void end(String sid, String token) {
        client.post().uri("/v1/sessions/{id}/end", sid)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange().expectStatus().isOk();
    }

    private Map<String, Object> latest(String token, String channel) {
        return getJson("/v1/sessions/latest?tenant=" + tenant + "&profileId=" + profile
                + (channel == null ? "" : "&channel=" + channel), token);
    }

    private Map<String, Object> getJson(String uri, String token) {
        return read(client.get().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
    }

    private void send(String sessionId, String token, Map<String, Object> body) {
        byte[] raw = client.post().uri("/v1/sessions/{id}/messages", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .bodyValue(body)
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();
        List<String> events = new ArrayList<>();
        if (raw != null) {
            for (String block : new String(raw, StandardCharsets.UTF_8).split("\n\n")) {
                for (String line : block.split("\n")) {
                    if (line.startsWith("event:")) events.add(line.substring(6).trim());
                }
            }
        }
        assertThat(events).contains("end").doesNotContain("error");
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
}
