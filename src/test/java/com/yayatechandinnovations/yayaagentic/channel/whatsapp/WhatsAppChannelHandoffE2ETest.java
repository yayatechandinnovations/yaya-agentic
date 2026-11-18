package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.yayatechandinnovations.yayaagentic.api.dto.AdminDtos;
import com.yayatechandinnovations.yayaagentic.api.dto.ChannelBindingDtos;
import com.yayatechandinnovations.yayaagentic.api.dto.WhatsAppSenderDtos;
import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.channel.binding.BindingStatus;
import com.yayatechandinnovations.yayaagentic.channel.binding.RoutingRule;
import com.yayatechandinnovations.yayaagentic.channel.binding.SessionPolicy;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio.TwilioSignatureVerifier;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.core.TurnChannel;
import com.yayatechandinnovations.yayaagentic.core.Turn;
import com.yayatechandinnovations.yayaagentic.engine.ConversationEngine;
import com.yayatechandinnovations.yayaagentic.engine.UserMessage;
import com.yayatechandinnovations.yayaagentic.engine.handoff.HandoffBlock;
import com.yayatechandinnovations.yayaagentic.operator_auth.TestAuthDance;
import com.yayatechandinnovations.yayaagentic.operator_auth.ratelimit.LoginRateLimiter;
import com.yayatechandinnovations.yayaagentic.persistence.InboundMessageRepository;
import com.yayatechandinnovations.yayaagentic.persistence.SessionRepository;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppSenderRepository;
import com.yayatechandinnovations.yayaagentic.profile.StartConversationRequest;
import com.yayatechandinnovations.yayaagentic.recorder.ConversationRecorder;
import com.yayatechandinnovations.yayaagentic.recorder.TurnRange;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * The WhatsApp side of the channel hand-off (design §1.2 C5, C7): closing
 * the channel row ends the engine session, and the greeting's «Seguir con lo
 * de la app» button copies the app conversation into the WhatsApp session
 * without a model call. Same context as {@link WhatsAppWebhookE2ETest}.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key=",
        "yaya.agentic.public-base-url=https://hooks.example",
        "yaya.agentic.channels.twilio.webhook-base-url=https://hooks.example",
        "yaya.agentic.channels.twilio.auth-token=test-auth-token",
        "yaya.agentic.channels.whatsapp.poller.enabled=false",
})
class WhatsAppChannelHandoffE2ETest {

    @Autowired WebTestClient client;
    @Autowired LoginRateLimiter rateLimiter;
    @Autowired InboundMessageRepository inbound;
    @Autowired ChannelSessionService channelSessions;
    @Autowired SessionRepository sessions;
    @Autowired ConversationEngine engine;
    @Autowired @Qualifier("postgresConversationRecorder") ConversationRecorder recorder;
    @Autowired WhatsAppSenderRepository senderRepo;
    @Value("${yaya.agentic.channels.twilio.webhook-base-url}") String webhookBase;
    @Value("${yaya.agentic.channels.twilio.auth-token}") String authToken;

    private static final String TENANT = "wa-handoff";
    private static final String PHONE = "+15559870001";
    private static final String SENDER_PHONE = "+15558000002";
    private static final AtomicReference<String> SENDER_ID = new AtomicReference<>();
    private static final String PROFILE_ID = "wa-handoff-profile";

    @BeforeEach
    void setUp() {
        rateLimiter.resetAll();
        client = TestAuthDance.asBootstrap(client);
        client.post().uri("/v1/admin/tenants").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new AdminDtos.TenantRequest(TENANT, "WA Handoff", "https://wa-handoff.example",
                        List.of(), List.of(), true, null, null, Map.of()))
                .exchange().expectStatus().value(s -> assertThat(s).isIn(201, 409));
        client.post().uri("/v1/admin/profiles").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new AdminDtos.ProfileRequest(TENANT, PROFILE_ID, PROFILE_ID, "Hola, soy Yaya.",
                        "prompt fragment", List.of(), null, null, Map.of()))
                .exchange().expectStatus().value(s -> assertThat(s).isIn(201, 409));
        if (SENDER_ID.get() == null) {
            var res = client.post().uri("/v1/admin/tenants/{t}/whatsapp-senders", TENANT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new WhatsAppSenderDtos.SenderRequest(SENDER_PHONE, "WA Handoff Co",
                            new WhatsAppSenderDtos.BusinessProfileRequest("WA Handoff Inc.", "WA Handoff Co",
                                    "ops@wa-handoff.example", "Address", "https://wa-handoff.example", "RETAIL",
                                    "Stuff."), null))
                    .exchange().expectStatus().isCreated()
                    .expectBody(WhatsAppSenderDtos.SenderResponse.class).returnResult().getResponseBody();
            SENDER_ID.set(res.id());
            senderRepo.findById(res.id()).ifPresent(e -> {
                e.setStatus("ACTIVE");
                e.setTwilioSenderSid("SR_HANDOFF_FAKE");
                senderRepo.save(e);
            });
            client.post().uri("/v1/admin/tenants/{t}/channel-bindings", TENANT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new ChannelBindingDtos.BindingRequest(
                            new ChannelBindingDtos.EndpointRequest("WHATSAPP_SENDER", SENDER_ID.get()),
                            PROFILE_ID, 1, new RoutingRule.MatchAny(), SessionPolicy.defaults(), 100,
                            BindingStatus.ACTIVE, null, null, null, null))
                    .exchange().expectStatus().value(s -> assertThat(s).isIn(201, 409));
        }
    }

    @Test
    void a_new_session_keyword_ends_the_engine_session_behind_the_closed_row() {
        post("SM_ho_a_" + UUID.randomUUID(), "Hola");
        Ids.SessionId first = openSession();

        post("SM_ho_b_" + UUID.randomUUID(), "restart");
        Ids.SessionId second = openSession();

        assertThat(second).isNotEqualTo(first);
        assertThat(sessions.findById(UUID.fromString(first.value()))).hasValueSatisfying(row ->
                assertThat(row.getEndedAt()).isNotNull());
        assertThat(sessions.findById(UUID.fromString(second.value()))).hasValueSatisfying(row ->
                assertThat(row.getEndedAt()).isNull());
    }

    @Test
    @SuppressWarnings("unchecked")
    void tapping_seguir_con_lo_de_la_app_copies_the_app_conversation_into_the_whatsapp_session() {
        // The same person (the channel's minted subject) was just in the app.
        Principal person = new Principal("whatsapp:" + PHONE, new Ids.TenantId(TENANT), Set.of(), Map.of(),
                Instant.now());
        AuthContext asPerson = new AuthContext(new Ids.TenantId(TENANT), Map.of(), Optional.of(person));
        Ids.SessionId app = engine.start(new StartConversationRequest(new Ids.TenantId(TENANT),
                Optional.of(Ids.ProfileId.latest(PROFILE_ID)), "admin-app", Map.of()),
                asPerson.onChannel(TurnChannel.APP)).session().id();
        engine.send(app, new UserMessage("revisa el ticket 4411", Map.of()), asPerson.onChannel(TurnChannel.APP))
                .blockLast(Duration.ofSeconds(20));
        List<Turn> appTurns = recorder.loadTurns(app, TurnRange.all());
        assertThat(appTurns).isNotEmpty();
        assertThat(appTurns.get(0).metadata().get("channel")).isEqualTo("admin-app");

        post("SM_ho_c_" + UUID.randomUUID(), "#new");
        Ids.SessionId wa = openSession();

        // The tap: Twilio sends the button's title as Body and its payload alongside.
        Map<String, String> tap = baseParams("SM_ho_d_" + UUID.randomUUID(), InboundDispatchWorker.HANDOFF_LABEL);
        tap.put("ButtonText", InboundDispatchWorker.HANDOFF_LABEL);
        tap.put("ButtonPayload", InboundDispatchWorker.HANDOFF_PREFIX + app.value());
        post(tap);

        List<Turn> waTurns = recorder.loadTurns(wa, TurnRange.all());
        assertThat(waTurns).hasSize(1);
        Turn copied = waTurns.get(0);
        assertThat(copied.role()).isEqualTo(Turn.Role.SYSTEM);
        assertThat(copied.metadata().get("origin")).isEqualTo(HandoffBlock.ORIGIN);
        assertThat(((Map<String, Object>) copied.metadata().get("handoff")).get("fromSessionId")).isEqualTo(app.value());
        assertThat(((Map<String, Object>) copied.metadata().get("handoff")).get("fromChannel")).isEqualTo("admin-app");
        assertThat(copied.content()).contains("revisa el ticket 4411").contains(HandoffBlock.instruction("admin-app"));
        // The app session is untouched and still open.
        assertThat(recorder.loadTurns(app, TurnRange.all())).hasSize(appTurns.size());
        assertThat(sessions.findById(UUID.fromString(app.value()))).hasValueSatisfying(row ->
                assertThat(row.getEndedAt()).isNull());

        // Declining is answered without the model and without a turn.
        Map<String, String> fresh = baseParams("SM_ho_e_" + UUID.randomUUID(), InboundDispatchWorker.FRESH_LABEL);
        fresh.put("ButtonText", InboundDispatchWorker.FRESH_LABEL);
        fresh.put("ButtonPayload", InboundDispatchWorker.FRESH_ID);
        post(fresh);
        assertThat(recorder.loadTurns(wa, TurnRange.all())).hasSize(1);
    }

    /* ---------------------------- helpers --------------------------------- */

    private Ids.SessionId openSession() {
        return channelSessions.findOpen(new Ids.TenantId(TENANT), new SenderId(SENDER_ID.get()), PHONE).orElseThrow();
    }

    private void post(String sid, String body) {
        post(baseParams(sid, body));
    }

    private void post(Map<String, String> params) {
        String sid = params.get("MessageSid");
        String fullUrl = webhookBase + "/v1/channels/whatsapp/twilio/webhook/" + SENDER_ID.get();
        String sig = TwilioSignatureVerifier.computeExpectedSignature(fullUrl, params, authToken);
        client.post().uri("/v1/channels/whatsapp/twilio/webhook/{sid}", SENDER_ID.get())
                .header("X-Twilio-Signature", sig)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue(asForm(params))
                .exchange().expectStatus().isOk();
        await().atMost(Duration.ofSeconds(20))
                .until(() -> inbound.findById(sid).map(e -> e.getProcessedAt() != null).orElse(false));
        assertThat(inbound.findById(sid).orElseThrow().getProcessingError()).isNull();
    }

    private Map<String, String> baseParams(String sid, String body) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("MessageSid", sid);
        p.put("From", "whatsapp:" + PHONE);
        p.put("To", "whatsapp:" + SENDER_PHONE);
        p.put("Body", body);
        p.put("NumMedia", "0");
        return p;
    }

    private MultiValueMap<String, String> asForm(Map<String, String> map) {
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        map.forEach(form::add);
        return form;
    }
}
