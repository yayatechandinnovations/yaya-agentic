package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.yayatechandinnovations.yayaagentic.api.TenantAuthResolverController;
import com.yayatechandinnovations.yayaagentic.api.TenantServiceTokenSecretController;
import com.yayatechandinnovations.yayaagentic.api.dto.AdminDtos;
import com.yayatechandinnovations.yayaagentic.api.dto.ChannelBindingDtos;
import com.yayatechandinnovations.yayaagentic.api.dto.WhatsAppKeywordActionDtos;
import com.yayatechandinnovations.yayaagentic.api.dto.WhatsAppSenderDtos;
import com.yayatechandinnovations.yayaagentic.channel.binding.BindingStatus;
import com.yayatechandinnovations.yayaagentic.channel.binding.OnUnresolvedPolicy;
import com.yayatechandinnovations.yayaagentic.channel.binding.RoutingRule;
import com.yayatechandinnovations.yayaagentic.channel.binding.SessionPolicy;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio.TwilioSignatureVerifier;
import com.yayatechandinnovations.yayaagentic.operator_auth.TestAuthDance;
import com.yayatechandinnovations.yayaagentic.operator_auth.ratelimit.LoginRateLimiter;
import com.yayatechandinnovations.yayaagentic.persistence.ChannelAutoReplyRepository;
import com.yayatechandinnovations.yayaagentic.persistence.InboundMessageRepository;
import com.yayatechandinnovations.yayaagentic.persistence.OutboundPendingEntity;
import com.yayatechandinnovations.yayaagentic.persistence.OutboundPendingRepository;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppSenderRepository;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * smart-lottery pos-ticket-whatsapp-share-design.md §8 P4 + P5 end to end:
 * the keyword-actions admin surface, an inbound keyword forwarded to the
 * tenant (signed) before any binding is consulted, and an unknown caller on
 * an AUTO_REPLY binding answered once per 24 h while the inbound is dropped
 * like REJECT does.
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
        // The tenant's "install" is a WireMock on loopback.
        "yaya.agentic.http-tools.allow-private-networks=true",
        // The service-token secret is stored encrypted; the admin PUT needs a master key.
        "yaya.agentic.secrets.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
})
class WhatsAppKeywordAndAutoReplyE2ETest {

    private static final String TENANT = "wa-keywords";
    private static final String BUSINESS_PHONE = "+15558000099";
    private static final String SERVICE_SECRET = "kw-service-secret-0123456789abcdef0123";
    private static final String AUTO_REPLY = "Gracias por escribir a Banca La Suerte. Este número envía copias de "
            + "tickets y avisos del negocio. Para atención, llama al 809-555-0100.";
    private static final AtomicReference<String> SENDER_ID = new AtomicReference<>();
    private static final AtomicReference<Long> RESOLVER_ID = new AtomicReference<>();
    private static final WireMockServer INSTALL = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @Autowired WebTestClient client;
    @Autowired LoginRateLimiter rateLimiter;
    @Autowired InboundMessageRepository inbound;
    @Autowired WhatsAppSenderRepository senderRows;
    @Autowired ChannelAutoReplyRepository autoReplies;
    @Autowired OutboundPendingRepository parked;
    @Value("${yaya.agentic.channels.twilio.webhook-base-url}") String webhookBase;
    @Value("${yaya.agentic.channels.twilio.auth-token}") String authToken;

    @BeforeEach
    void setUp() {
        if (!INSTALL.isRunning()) INSTALL.start();
        rateLimiter.resetAll();
        client = TestAuthDance.asBootstrap(client);
        client.post().uri("/v1/admin/tenants").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new AdminDtos.TenantRequest(TENANT, "WA keywords", INSTALL.baseUrl(),
                        List.of(), List.of(), false, null, null, Map.of()))
                .exchange().expectStatus().value(s -> assertThat(s).isIn(201, 409));
        client.put().uri("/v1/admin/tenants/{t}/service-token-secret", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new TenantServiceTokenSecretController.SetRequest(SERVICE_SECRET))
                .exchange().expectStatus().is2xxSuccessful();
        if (SENDER_ID.get() == null) {
            var res = client.post().uri("/v1/admin/tenants/{t}/whatsapp-senders", TENANT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new WhatsAppSenderDtos.SenderRequest(BUSINESS_PHONE, "Banca La Suerte",
                            new WhatsAppSenderDtos.BusinessProfileRequest("Banca La Suerte SRL", "Banca La Suerte",
                                    "ops@banca.example", "Address", "https://banca.example", "RETAIL", "Tickets."),
                            null))
                    .exchange().expectStatus().isCreated()
                    .expectBody(WhatsAppSenderDtos.SenderResponse.class).returnResult().getResponseBody();
            SENDER_ID.set(res.id());
            // Tests flip the row ACTIVE directly; the service path would call Twilio.
            senderRows.findById(res.id()).ifPresent(e -> {
                e.setStatus("ACTIVE");
                e.setTwilioSenderSid("SR_KW_FAKE");
                senderRows.save(e);
            });
        }
    }

    @AfterAll
    static void stopInstall() {
        INSTALL.stop();
    }

    /* ------------------------------ P5 ------------------------------------ */

    @Test
    void keyword_actions_admin_round_trip_replaces_the_set_and_validates_urls() {
        String url = INSTALL.baseUrl() + "/integrations/keywords";
        var set = new WhatsAppKeywordActionDtos.ActionSet(List.of(
                new WhatsAppKeywordActionDtos.ActionItem("ticket", url),
                new WhatsAppKeywordActionDtos.ActionItem("No es mío", url),
                new WhatsAppKeywordActionDtos.ActionItem("NO MAS TICKETS", url)));
        var saved = client.put().uri("/v1/admin/tenants/{t}/whatsapp-senders/{s}/keyword-actions", TENANT, SENDER_ID.get())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(set)
                .exchange().expectStatus().isOk()
                .expectBody(WhatsAppKeywordActionDtos.ActionSet.class).returnResult().getResponseBody();
        assertThat(saved.actions()).extracting(WhatsAppKeywordActionDtos.ActionItem::prefix)
                .containsExactly("TICKET", "NO ES MIO", "NO MAS TICKETS");

        var listed = client.get().uri("/v1/admin/tenants/{t}/whatsapp-senders/{s}/keyword-actions", TENANT, SENDER_ID.get())
                .exchange().expectStatus().isOk()
                .expectBody(WhatsAppKeywordActionDtos.ActionSet.class).returnResult().getResponseBody();
        assertThat(listed.actions()).hasSize(3);
        assertThat(listed.actions().get(0).forwardUrl()).isEqualTo(url);

        client.put().uri("/v1/admin/tenants/{t}/whatsapp-senders/{s}/keyword-actions", TENANT, SENDER_ID.get())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new WhatsAppKeywordActionDtos.ActionSet(List.of(
                        new WhatsAppKeywordActionDtos.ActionItem("TICKET", "/integrations/keywords"))))
                .exchange().expectStatus().isBadRequest();

        // An unknown sender of this tenant is not found; the set above stays.
        client.get().uri("/v1/admin/tenants/{t}/whatsapp-senders/{s}/keyword-actions", TENANT, "nope")
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void keyword_inbound_is_forwarded_signed_and_answered_without_a_binding() {
        String url = INSTALL.baseUrl() + "/integrations/keywords";
        client.put().uri("/v1/admin/tenants/{t}/whatsapp-senders/{s}/keyword-actions", TENANT, SENDER_ID.get())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new WhatsAppKeywordActionDtos.ActionSet(List.of(
                        new WhatsAppKeywordActionDtos.ActionItem("TICKET", url))))
                .exchange().expectStatus().isOk();
        INSTALL.stubFor(post(urlEqualTo("/integrations/keywords"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"reply\":\"Listo, tu ticket va en camino.\"}")));

        String phone = "+1555" + (1000000 + new java.util.Random().nextInt(8999999));
        String sid = postInbound(phone, "  Tícket  7G4K2 ");
        await().atMost(Duration.ofSeconds(20))
                .until(() -> inbound.findById(sid).map(e -> e.getProcessedAt() != null).orElse(false));

        var row = inbound.findById(sid).orElseThrow();
        // No binding exists for this sender: without the hook this would be no_binding_matched.
        assertThat(row.getProcessingError()).isNull();
        assertThat(row.getBodyRedacted()).contains("7G4K2");

        var forwards = INSTALL.findAll(postRequestedFor(urlEqualTo("/integrations/keywords"))
                .withRequestBody(matchingJsonPath("$.messageSid", equalTo(sid))));
        assertThat(forwards).hasSize(1);
        var body = forwards.get(0).getBodyAsString();
        assertThat(body).contains("\"tenantId\":\"" + TENANT + "\"")
                .contains("\"senderId\":\"" + SENDER_ID.get() + "\"")
                .contains("\"from\":\"" + phone + "\"")
                .contains("\"to\":\"" + BUSINESS_PHONE + "\"")
                .contains("\"text\":\"Tícket  7G4K2\"")
                .contains("\"receivedAt\":\"");
        String token = forwards.get(0).getHeader("X-Yaya-Service-Token");
        assertThat(token).startsWith(TENANT + ".keyword:" + SENDER_ID.get() + ".");
        assertThat(token.split("\\.")[3]).isEqualTo("keyword:forward");

        // A plain message of the same phone is not a keyword: it reaches binding matching (none here).
        String sid2 = postInbound(phone, "hola, buenas");
        await().atMost(Duration.ofSeconds(20))
                .until(() -> inbound.findById(sid2).map(e -> e.getProcessedAt() != null).orElse(false));
        assertThat(inbound.findById(sid2).orElseThrow().getProcessingError()).isEqualTo("no_binding_matched");
        assertThat(INSTALL.findAll(postRequestedFor(urlEqualTo("/integrations/keywords"))
                .withRequestBody(matchingJsonPath("$.messageSid", equalTo(sid2))))).isEmpty();
    }

    @Test
    void keyword_inbound_releases_what_was_parked_for_the_caller() {
        String url = INSTALL.baseUrl() + "/integrations/keywords";
        client.put().uri("/v1/admin/tenants/{t}/whatsapp-senders/{s}/keyword-actions", TENANT, SENDER_ID.get())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new WhatsAppKeywordActionDtos.ActionSet(List.of(
                        new WhatsAppKeywordActionDtos.ActionItem("TICKET", url))))
                .exchange().expectStatus().isOk();
        INSTALL.stubFor(post(urlEqualTo("/integrations/keywords"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"reply\":\"Listo.\"}")));
        String phone = "+1555" + (1000000 + new java.util.Random().nextInt(8999999));
        // Queued while the 24 h window was closed (§10.2); the keyword inbound reopens it.
        OutboundPendingEntity waiting = parked.save(new OutboundPendingEntity(TENANT, SENDER_ID.get(), phone,
                UUID.randomUUID().toString(), "Tu ticket de ayer fue premiado.", null));
        assertThat(waiting.getReleasedAt()).isNull();

        String sid = postInbound(phone, "ticket 7G4K2");
        await().atMost(Duration.ofSeconds(20))
                .until(() -> inbound.findById(sid).map(e -> e.getProcessedAt() != null).orElse(false));

        assertThat(inbound.findById(sid).orElseThrow().getProcessingError()).isNull();
        assertThat(parked.findById(waiting.getId()).orElseThrow().getReleasedAt()).isNotNull();
    }

    /* ------------------------------ P4 ------------------------------------ */

    @Test
    void auto_reply_binding_answers_an_unknown_caller_once_and_drops_the_inbound_like_reject() {
        ensureProfile("wa-keywords-profile");
        ensureResolver();
        INSTALL.stubFor(post(urlEqualTo("/yaya/resolve-staff")).willReturn(aResponse().withStatus(404)));
        var req = new ChannelBindingDtos.BindingRequest(
                new ChannelBindingDtos.EndpointRequest("WHATSAPP_SENDER", SENDER_ID.get()),
                "wa-keywords-profile", 1, new RoutingRule.MatchAny(), SessionPolicy.defaults(), 100,
                BindingStatus.ACTIVE, RESOLVER_ID.get(), OnUnresolvedPolicy.AUTO_REPLY, null, null, AUTO_REPLY);
        var binding = client.post().uri("/v1/admin/tenants/{t}/channel-bindings", TENANT)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(req)
                .exchange().expectStatus().isCreated()
                .expectBody(ChannelBindingDtos.BindingResponse.class).returnResult().getResponseBody();
        assertThat(binding.onUnresolved()).isEqualTo(OnUnresolvedPolicy.AUTO_REPLY);
        assertThat(binding.unresolvedReplyText()).isEqualTo(AUTO_REPLY);
        try {
            String phone = "+1555" + (1000000 + new java.util.Random().nextInt(8999999));
            String sid = postInbound(phone, "hola, quiero comprar");
            await().atMost(Duration.ofSeconds(20))
                    .until(() -> inbound.findById(sid).map(e -> e.getProcessedAt() != null).orElse(false));
            assertThat(inbound.findById(sid).orElseThrow().getProcessingError()).isEqualTo("auth_resolver_rejected");
            var claim = autoReplies.findBySenderIdAndEndUserPhone(SENDER_ID.get(), phone).orElseThrow();
            OffsetDateTime first = claim.getRepliedAt();

            String sid2 = postInbound(phone, "hola?");
            await().atMost(Duration.ofSeconds(20))
                    .until(() -> inbound.findById(sid2).map(e -> e.getProcessedAt() != null).orElse(false));
            assertThat(inbound.findById(sid2).orElseThrow().getProcessingError()).isEqualTo("auth_resolver_rejected");
            assertThat(autoReplies.findBySenderIdAndEndUserPhone(SENDER_ID.get(), phone).orElseThrow().getRepliedAt())
                    .isEqualTo(first);
        } finally {
            client.delete().uri("/v1/admin/tenants/{t}/channel-bindings/{id}", TENANT, binding.id())
                    .exchange().expectStatus().isNoContent();
        }
    }

    @Test
    void auto_reply_needs_its_text_and_an_update_can_clear_it() {
        ensureProfile("wa-keywords-profile");
        var noText = new ChannelBindingDtos.BindingRequest(
                new ChannelBindingDtos.EndpointRequest("WHATSAPP_SENDER", SENDER_ID.get()),
                "wa-keywords-profile", 1, new RoutingRule.MatchAny(), SessionPolicy.defaults(), 100,
                BindingStatus.ACTIVE, null, OnUnresolvedPolicy.AUTO_REPLY, null, null, "   ");
        client.post().uri("/v1/admin/tenants/{t}/channel-bindings", TENANT)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(noText)
                .exchange().expectStatus().isBadRequest();

        var ok = client.post().uri("/v1/admin/tenants/{t}/channel-bindings", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ChannelBindingDtos.BindingRequest(
                        new ChannelBindingDtos.EndpointRequest("WHATSAPP_SENDER", SENDER_ID.get()),
                        "wa-keywords-profile", 1, new RoutingRule.MatchAny(), SessionPolicy.defaults(), 900,
                        BindingStatus.ACTIVE, null, OnUnresolvedPolicy.AUTO_REPLY, null, null, AUTO_REPLY))
                .exchange().expectStatus().isCreated()
                .expectBody(ChannelBindingDtos.BindingResponse.class).returnResult().getResponseBody();
        try {
            // An older client's PUT (no text field) keeps the text.
            var kept = client.put().uri("/v1/admin/tenants/{t}/channel-bindings/{id}", TENANT, ok.id())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new ChannelBindingDtos.BindingUpdate("wa-keywords-profile", 1, new RoutingRule.MatchAny(),
                            SessionPolicy.defaults(), 900, BindingStatus.ACTIVE, null, OnUnresolvedPolicy.AUTO_REPLY,
                            null, null))
                    .exchange().expectStatus().isOk()
                    .expectBody(ChannelBindingDtos.BindingResponse.class).returnResult().getResponseBody();
            assertThat(kept.unresolvedReplyText()).isEqualTo(AUTO_REPLY);

            // Switching to REJECT with "" clears it.
            var cleared = client.put().uri("/v1/admin/tenants/{t}/channel-bindings/{id}", TENANT, ok.id())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new ChannelBindingDtos.BindingUpdate("wa-keywords-profile", 1, new RoutingRule.MatchAny(),
                            SessionPolicy.defaults(), 900, BindingStatus.ACTIVE, null, OnUnresolvedPolicy.REJECT,
                            null, null, ""))
                    .exchange().expectStatus().isOk()
                    .expectBody(ChannelBindingDtos.BindingResponse.class).returnResult().getResponseBody();
            assertThat(cleared.onUnresolved()).isEqualTo(OnUnresolvedPolicy.REJECT);
            assertThat(cleared.unresolvedReplyText()).isNull();
        } finally {
            client.delete().uri("/v1/admin/tenants/{t}/channel-bindings/{id}", TENANT, ok.id())
                    .exchange().expectStatus().isNoContent();
        }
    }

    /* ---------------------------- helpers --------------------------------- */

    private void ensureProfile(String pid) {
        client.post().uri("/v1/admin/profiles").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new AdminDtos.ProfileRequest(TENANT, pid, pid, "intro", "prompt",
                        List.of(), null, null, Map.of()))
                .exchange().expectStatus().value(s -> assertThat(s).isIn(201, 409));
    }

    private void ensureResolver() {
        if (RESOLVER_ID.get() != null) return;
        var resp = client.post().uri("/v1/admin/tenants/{t}/auth-resolvers", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new TenantAuthResolverController.ResolverRequest(
                        "staff-" + UUID.randomUUID().toString().substring(0, 8), "staff resolver", "HTTP", null,
                        "/yaya/resolve-staff", "NONE", null, null, null, 2500, false, null, 60, 3600,
                        "REJECT", "ACTIVE"))
                .exchange().expectStatus().isCreated()
                .expectBody(TenantAuthResolverController.ResolverResponse.class).returnResult().getResponseBody();
        RESOLVER_ID.set(resp.id());
    }

    private String postInbound(String phone, String body) {
        String sid = "SM_kw_" + UUID.randomUUID();
        Map<String, String> p = new LinkedHashMap<>();
        p.put("MessageSid", sid);
        p.put("From", "whatsapp:" + phone);
        p.put("To", "whatsapp:" + BUSINESS_PHONE);
        p.put("Body", body);
        p.put("NumMedia", "0");
        String fullUrl = webhookBase + "/v1/channels/whatsapp/twilio/webhook/" + SENDER_ID.get();
        String sig = TwilioSignatureVerifier.computeExpectedSignature(fullUrl, p, authToken);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        p.forEach(form::add);
        client.post().uri("/v1/channels/whatsapp/twilio/webhook/{sid}", SENDER_ID.get())
                .header("X-Twilio-Signature", sig)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue(form)
                .exchange().expectStatus().isOk();
        return sid;
    }
}
