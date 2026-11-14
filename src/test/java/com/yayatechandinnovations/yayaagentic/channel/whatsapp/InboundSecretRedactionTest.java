package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.yayatechandinnovations.yayaagentic.api.dto.AdminDtos;
import com.yayatechandinnovations.yayaagentic.api.dto.ChannelBindingDtos;
import com.yayatechandinnovations.yayaagentic.api.dto.WhatsAppSenderDtos;
import com.yayatechandinnovations.yayaagentic.channel.binding.BindingStatus;
import com.yayatechandinnovations.yayaagentic.channel.binding.RoutingRule;
import com.yayatechandinnovations.yayaagentic.channel.binding.SessionPolicy;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.engine.secret.SecretPrompts;
import com.yayatechandinnovations.yayaagentic.memory.WorkingMemory;
import com.yayatechandinnovations.yayaagentic.operator_auth.TestAuthDance;
import com.yayatechandinnovations.yayaagentic.operator_auth.ratelimit.LoginRateLimiter;
import com.yayatechandinnovations.yayaagentic.persistence.InboundMessageEntity;
import com.yayatechandinnovations.yayaagentic.persistence.InboundMessageRepository;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppSenderRepository;
import com.yayatechandinnovations.yayaagentic.recorder.ConversationRecorder;
import com.yayatechandinnovations.yayaagentic.recorder.TurnRange;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * Review 2R, R3 + R5 on WhatsApp ({@code docs/guides/admin-panel-sessions.md}
 * §15): once a code prompt is pending for the conversation, a message that may
 * hold the code is taken out of the stored row whichever way the worker
 * leaves — a normal turn, a conversation a person holds, a failure.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key=",
        "yaya.agentic.channels.whatsapp.poller.enabled=false",
})
class InboundSecretRedactionTest {

    private static final String TENANT = "wa-secret";
    private static final String BUSINESS_PHONE = "+15558000077";
    private static final String CODE = "481516";
    private static final String MARKER = "(código ingresado)";
    private static final AtomicReference<String> SENDER_ID = new AtomicReference<>();

    @Autowired WebTestClient client;
    @Autowired LoginRateLimiter rateLimiter;
    @Autowired InboundDispatchWorker worker;
    @Autowired InboundMessageRepository inbound;
    @Autowired ChannelSessionService channelSessions;
    @Autowired ConversationHoldService holds;
    @Autowired WorkingMemory workingMemory;
    @Autowired ConversationRecorder recorder;
    @Autowired WhatsAppSenderRepository senderRows;

    /** One customer per test, so each has its own conversation. */
    private String phone;

    @BeforeEach
    void setUp() {
        rateLimiter.resetAll();
        client = TestAuthDance.asBootstrap(client);
        client.post().uri("/v1/admin/tenants").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new AdminDtos.TenantRequest(TENANT, "WA secret", "https://wa-secret.example",
                        List.of(), List.of(), true, null, null, Map.of()))
                .exchange().expectStatus().value(s -> assertThat(s).isIn(201, 409));
        client.post().uri("/v1/admin/profiles").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new AdminDtos.ProfileRequest(TENANT, "wa-secret-profile", "wa-secret-profile",
                        "intro", "prompt fragment", List.of(), null, null, Map.of()))
                .exchange().expectStatus().value(s -> assertThat(s).isIn(201, 409));
        if (SENDER_ID.get() == null) {
            var created = client.post().uri("/v1/admin/tenants/{t}/whatsapp-senders", TENANT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new WhatsAppSenderDtos.SenderRequest(BUSINESS_PHONE, "WA secret Co",
                            new WhatsAppSenderDtos.BusinessProfileRequest("WA secret Inc.", "WA secret Co",
                                    "ops@wa-secret.example", "Address", "https://wa-secret.example", "RETAIL",
                                    "Stuff."), null))
                    .exchange().expectStatus().isCreated()
                    .expectBody(WhatsAppSenderDtos.SenderResponse.class).returnResult().getResponseBody();
            SENDER_ID.set(created.id());
            senderRows.findById(created.id()).ifPresent(e -> {
                e.setStatus("ACTIVE");
                e.setTwilioSenderSid("SR_SECRET_FAKE");
                senderRows.save(e);
            });
        }
        client.post().uri("/v1/admin/tenants/{t}/channel-bindings", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ChannelBindingDtos.BindingRequest(
                        new ChannelBindingDtos.EndpointRequest("WHATSAPP_SENDER", SENDER_ID.get()),
                        "wa-secret-profile", 1, new RoutingRule.MatchAny(), SessionPolicy.defaults(), 100,
                        BindingStatus.ACTIVE, null, null, null, null))
                .exchange().expectStatus().value(s -> assertThat(s).isIn(201, 409));
        phone = "+1555" + String.format("%07d", (int) (Math.random() * 9_999_999));
    }

    @Test
    void a_code_inside_a_sentence_is_not_kept_in_the_row_or_the_conversation() {
        Ids.SessionId session = conversationWithPendingPrompt();

        InboundMessageEntity row = receive("mi código es " + CODE);

        assertThat(row.getProcessingError()).isNull();
        assertRedacted(row);
        assertThat(String.valueOf(recorder.loadTurns(session, TurnRange.all()))).doesNotContain(CODE);
    }

    @Test
    void a_number_of_the_wrong_length_is_not_kept_either_and_the_prompt_stays() {
        Ids.SessionId session = conversationWithPendingPrompt();

        InboundMessageEntity row = receive("90271833");

        assertThat(row.getRawPayloadJson()).doesNotContain("90271833");
        assertThat(row.getBodyRedacted()).isEqualTo(MARKER);
        assertThat(String.valueOf(recorder.loadTurns(session, TurnRange.all()))).doesNotContain("90271833");
        assertThat(workingMemory.get(session).get(SecretPrompts.SLOT)).isInstanceOf(Map.class);
    }

    @Test
    void a_code_sent_while_a_person_holds_the_conversation_is_not_kept() {
        conversationWithPendingPrompt();
        holds.hold(new Ids.TenantId(TENANT), phone, Duration.ofMinutes(30), "handoff");
        try {
            InboundMessageEntity row = receive("mi código es " + CODE);

            assertThat(row.getProcessingError()).isEqualTo("human_hold");
            assertRedacted(row);
        } finally {
            holds.release(new Ids.TenantId(TENANT), phone);
        }
    }

    @Test
    void a_message_marked_failed_keeps_no_copy_of_the_code() {
        conversationWithPendingPrompt();
        String sid = "SM_sec_" + UUID.randomUUID();
        inbound.save(new InboundMessageEntity(sid, TENANT, SENDER_ID.get(), phone, 0, payload(sid, CODE)));

        worker.markFailed(sid, "enqueue_rejected: pool is full");

        InboundMessageEntity row = inbound.findById(sid).orElseThrow();
        assertThat(row.getProcessingError()).startsWith("enqueue_rejected");
        assertThat(row.getRawPayloadJson()).doesNotContain(CODE).contains(MARKER);
    }

    @Test
    void without_a_pending_prompt_the_row_is_kept_as_it_was() {
        receive("hola");

        InboundMessageEntity row = receive("mi pedido es el " + CODE);

        assertThat(row.getRawPayloadJson()).contains(CODE);
        assertThat(row.getBodyRedacted()).contains(CODE);
    }

    // ------------------------------------------------------------------

    private void assertRedacted(InboundMessageEntity row) {
        assertThat(row.getRawPayloadJson()).doesNotContain(CODE).contains(MARKER);
        assertThat(row.getBodyRedacted()).isEqualTo(MARKER);
    }

    /** Opens the customer's conversation and leaves a six-digit code prompt waiting in it. */
    private Ids.SessionId conversationWithPendingPrompt() {
        receive("hola");
        Ids.SessionId session = ((ChannelSessionService.Outcome.Reuse) channelSessions.openOrReuse(
                new Ids.TenantId(TENANT), new SenderId(SENDER_ID.get()), phone, "ping",
                SessionPolicy.defaults())).existing();
        Map<String, Object> frame = SecretPrompts.frame("call-1", Map.of("format", "digits", "length", 6,
                "message", "Escribe el código."), "es");
        workingMemory.merge(session, Map.of(SecretPrompts.SLOT,
                SecretPrompts.pending("no_such_tool", Map.of(), frame, "turn-0", Instant.now())));
        return session;
    }

    private InboundMessageEntity receive(String body) {
        String sid = "SM_sec_" + UUID.randomUUID();
        inbound.save(new InboundMessageEntity(sid, TENANT, SENDER_ID.get(), phone, 0, payload(sid, body)));
        worker.processSync(sid);
        InboundMessageEntity row = inbound.findById(sid).orElseThrow();
        assertThat(row.getProcessedAt()).isNotNull();
        return row;
    }

    private String payload(String sid, String body) {
        return "{\"MessageSid\":\"" + sid + "\",\"From\":\"whatsapp:" + phone + "\",\"To\":\"whatsapp:"
                + BUSINESS_PHONE + "\",\"Body\":\"" + body + "\",\"NumMedia\":\"0\"}";
    }
}
