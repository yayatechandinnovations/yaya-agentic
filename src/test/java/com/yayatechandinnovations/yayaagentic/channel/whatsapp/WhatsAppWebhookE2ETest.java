package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.api.dto.AdminDtos;
import com.yayatechandinnovations.yayaagentic.api.dto.ChannelBindingDtos;
import com.yayatechandinnovations.yayaagentic.api.dto.WhatsAppSenderDtos;
import com.yayatechandinnovations.yayaagentic.channel.binding.BindingStatus;
import com.yayatechandinnovations.yayaagentic.channel.binding.RoutingRule;
import com.yayatechandinnovations.yayaagentic.channel.binding.SessionPolicy;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio.TwilioSignatureVerifier;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.entity.EntityMemory;
import com.yayatechandinnovations.yayaagentic.core.entity.EntityRef;
import com.yayatechandinnovations.yayaagentic.operator_auth.TestAuthDance;
import com.yayatechandinnovations.yayaagentic.operator_auth.ratelimit.LoginRateLimiter;
import com.yayatechandinnovations.yayaagentic.persistence.InboundMessageRepository;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * E2E coverage for the W4 inbound flow: Twilio POSTs (we forge a properly-
 * signed payload), the webhook controller validates, dedups, persists, and
 * hands off to the worker; the worker runs the engine and writes
 * EntityMemory + channel_sessions.
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
class WhatsAppWebhookE2ETest {

    @Autowired WebTestClient client;
    @Autowired LoginRateLimiter rateLimiter;
    @Autowired InboundDispatchWorker worker;
    @Autowired InboundMessageRepository inbound;
    @Autowired EntityMemory entityMemory;
    @Autowired ObjectMapper mapper;
    @Autowired ChannelSessionService channelSessions;
    @Value("${yaya.agentic.channels.twilio.webhook-base-url}") String webhookBase;
    @Value("${yaya.agentic.channels.twilio.auth-token}") String authToken;

    private static final String TENANT = "wa-e2e";
    private static final AtomicReference<String> SENDER_ID = new AtomicReference<>();
    private static final AtomicReference<String> PROFILE_ID = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        rateLimiter.resetAll();
        client = TestAuthDance.asBootstrap(client);
        ensureTenant();
        ensureProfile();
        ensureSenderActive();
        ensureBinding();
    }

    private void ensureTenant() {
        var req = new AdminDtos.TenantRequest(
                TENANT, "WA E2E", "https://wa-e2e.example",
                List.of(), List.of(), true, null, null, Map.of());
        client.post().uri("/v1/admin/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req).exchange()
                .expectStatus().value(s -> assertThat(s).isIn(201, 409));
    }

    private void ensureProfile() {
        if (PROFILE_ID.get() != null) return;
        String pid = "wa-e2e-profile";
        var req = new AdminDtos.ProfileRequest(
                TENANT, pid, pid, "intro one-liner", "prompt fragment",
                List.of(), null, null, Map.of());
        client.post().uri("/v1/admin/profiles")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req).exchange()
                .expectStatus().value(s -> assertThat(s).isIn(201, 409));
        PROFILE_ID.set(pid);
    }

    private void ensureSenderActive() {
        if (SENDER_ID.get() != null) return;
        var req = new WhatsAppSenderDtos.SenderRequest(
                "+15558000001",
                "WA E2E Co",
                new WhatsAppSenderDtos.BusinessProfileRequest(
                        "WA E2E Inc.", "WA E2E Co", "ops@wa-e2e.example",
                        "Address", "https://wa-e2e.example", "RETAIL", "Stuff."),
                null);
        var res = client.post()
                .uri("/v1/admin/tenants/{t}/whatsapp-senders", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req).exchange()
                .expectStatus().isCreated()
                .expectBody(WhatsAppSenderDtos.SenderResponse.class)
                .returnResult().getResponseBody();
        SENDER_ID.set(res.id());

        // Forcibly flip the sender into ACTIVE by going through the
        // service directly — submit would hit Twilio, which isn't stubbed
        // here. Production never does this; tests do.
        forceSenderActive(res.id());
    }

    /** Backdoor that flips the row to ACTIVE without calling Twilio. The
     *  service path requires a real Twilio call; this test exercises the
     *  ACTIVE-sender flow without the BSP integration. */
    private void forceSenderActive(String senderId) {
        repo.findById(senderId).ifPresent(e -> {
            e.setStatus("ACTIVE");
            e.setTwilioSenderSid("SR_E2E_FAKE");
            repo.save(e);
        });
    }

    @Autowired
    com.yayatechandinnovations.yayaagentic.persistence.WhatsAppSenderRepository repo;

    private void ensureBinding() {
        var req = new ChannelBindingDtos.BindingRequest(
                new ChannelBindingDtos.EndpointRequest("WHATSAPP_SENDER", SENDER_ID.get()),
                PROFILE_ID.get(), 1,
                new RoutingRule.MatchAny(),
                SessionPolicy.defaults(),
                100,
                BindingStatus.ACTIVE,
                null, null);
        client.post()
                .uri("/v1/admin/tenants/{t}/channel-bindings", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req).exchange()
                .expectStatus().value(s -> assertThat(s).isIn(201, 409));
    }

    @Test
    void webhook_with_invalid_signature_returns_403() {
        Map<String, String> params = baseParams("SM_bad_001", "Hello");
        client.post()
                .uri("/v1/channels/whatsapp/twilio/webhook/{sid}", SENDER_ID.get())
                .header("X-Twilio-Signature", "not-the-real-thing")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue(asForm(params))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(inbound.existsById("SM_bad_001")).isFalse();
    }

    @Test
    void webhook_with_valid_signature_acks_persists_and_runs_worker() {
        String messageSid = "SM_ok_" + UUID.randomUUID();
        Map<String, String> params = baseParams(messageSid, "Hello there");

        String fullUrl = webhookBase + "/v1/channels/whatsapp/twilio/webhook/" + SENDER_ID.get();
        String sig = TwilioSignatureVerifier.computeExpectedSignature(fullUrl, params, authToken);

        client.post()
                .uri("/v1/channels/whatsapp/twilio/webhook/{sid}", SENDER_ID.get())
                .header("X-Twilio-Signature", sig)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue(asForm(params))
                .exchange()
                .expectStatus().isOk();

        // Worker is async — poll until processed.
        await().atMost(java.time.Duration.ofSeconds(20))
                .until(() -> inbound.findById(messageSid).map(e -> e.getProcessedAt() != null).orElse(false));

        var row = inbound.findById(messageSid).orElseThrow();
        assertThat(row.getProcessedAt()).isNotNull();
        assertThat(row.getProcessingError()).isNull();
        assertThat(row.getEndUserPhone()).isEqualTo("+15559876543");

        // Channel session was opened
        var openSessions = channelSessions.openOrReuse(
                new Ids.TenantId(TENANT),
                new SenderId(SENDER_ID.get()),
                "+15559876543",
                "ping",
                SessionPolicy.defaults());
        assertThat(openSessions).isInstanceOf(ChannelSessionService.Outcome.Reuse.class);

        Ids.SessionId sessionId = ((ChannelSessionService.Outcome.Reuse) openSessions).existing();
        List<EntityRef> caps = entityMemory.all(sessionId);
        assertThat(caps).extracting(EntityRef::name)
                .contains("endUserPhone", "endUserChannel");
        EntityRef phoneEnt = caps.stream().filter(e -> e.name().equals("endUserPhone"))
                .findFirst().orElseThrow();
        assertThat(phoneEnt.value()).isEqualTo("+15559876543");
        assertThat(phoneEnt.producedBy().value()).isEqualTo("__channel_whatsapp_inbound__");
    }

    @Test
    void duplicate_message_sid_is_idempotent() {
        String messageSid = "SM_dup_" + UUID.randomUUID();
        Map<String, String> params = baseParams(messageSid, "Hi again");
        String fullUrl = webhookBase + "/v1/channels/whatsapp/twilio/webhook/" + SENDER_ID.get();
        String sig = TwilioSignatureVerifier.computeExpectedSignature(fullUrl, params, authToken);

        for (int i = 0; i < 2; i++) {
            client.post()
                    .uri("/v1/channels/whatsapp/twilio/webhook/{sid}", SENDER_ID.get())
                    .header("X-Twilio-Signature", sig)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue(asForm(params))
                    .exchange()
                    .expectStatus().isOk();
        }
        // Only one row exists.
        assertThat(inbound.findById(messageSid)).isPresent();
    }

    /* ---------------------------- helpers --------------------------------- */

    private Map<String, String> baseParams(String sid, String body) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("MessageSid", sid);
        p.put("From", "whatsapp:+15559876543");
        p.put("To", "whatsapp:+15558000001");
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
