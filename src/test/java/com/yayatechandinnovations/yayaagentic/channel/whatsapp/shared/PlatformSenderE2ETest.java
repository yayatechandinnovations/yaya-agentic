package com.yayatechandinnovations.yayaagentic.channel.whatsapp.shared;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.yayatechandinnovations.yayaagentic.api.TenantAuthResolverController;
import com.yayatechandinnovations.yayaagentic.api.TenantServiceTokenSecretController;
import com.yayatechandinnovations.yayaagentic.api.dto.AdminDtos;
import com.yayatechandinnovations.yayaagentic.api.dto.ChannelBindingDtos;
import com.yayatechandinnovations.yayaagentic.api.dto.PlatformSenderDtos;
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
import com.yayatechandinnovations.yayaagentic.persistence.InboundMessageEntity;
import com.yayatechandinnovations.yayaagentic.persistence.InboundMessageRepository;
import com.yayatechandinnovations.yayaagentic.persistence.OutboundMessageEntity;
import com.yayatechandinnovations.yayaagentic.persistence.OutboundMessageRepository;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppSenderRepository;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppSharedRouteRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

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
 * whatsapp-shared-sender-design.md end to end: a platform-owned number that
 * platform admins assign to workspaces — the admin surfaces, the workspace's
 * read-only view, keyword scopes, and the router picking the workspace of an
 * inbound (phone lookup, ROUTED keyword, pin, the «¿Con cuál negocio?»
 * question and its replay) with only staff reaching an assistant.
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
        "yaya.agentic.http-tools.allow-private-networks=true",
        "yaya.agentic.secrets.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
})
class PlatformSenderE2ETest {

    private static final String SERVICE_SECRET = "ps-service-secret-0123456789abcdef0123";
    private static final WireMockServer INSTALL = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @Autowired WebTestClient base;
    @Autowired LoginRateLimiter rateLimiter;
    @Autowired WhatsAppSenderRepository senderRows;
    @Autowired InboundMessageRepository inbound;
    @Autowired OutboundMessageRepository outbound;
    @Autowired WhatsAppSharedRouteRepository routes;
    @Autowired ChannelAutoReplyRepository autoReplies;
    @Autowired com.yayatechandinnovations.yayaagentic.persistence.ChannelOptOutRepository optOutRows;
    @Autowired com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSenderService senderService;
    @Value("${yaya.agentic.channels.twilio.webhook-base-url}") String webhookBase;
    @Value("${yaya.agentic.channels.twilio.auth-token}") String authToken;

    private WebTestClient admin;
    private String a;
    private String b;
    private String c;
    private String senderId;
    private String senderPhone;

    @BeforeEach
    void setUp() {
        if (!INSTALL.isRunning()) INSTALL.start();
        INSTALL.resetAll();
        rateLimiter.resetAll();
        admin = TestAuthDance.asBootstrap(base);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        a = tenant("ps-a-" + suffix);
        b = tenant("ps-b-" + suffix);
        c = tenant("ps-c-" + suffix);
        senderPhone = randomPhone();
        senderId = createPlatformSender(senderPhone);
        assign(a, "Banca A " + suffix);
        assign(b, "Banca B " + suffix);
    }

    @AfterAll
    static void stopInstall() {
        INSTALL.stop();
    }

    /* ------------------------------ admin ------------------------------- */

    @Test
    void platform_admins_own_the_number_and_workspaces_only_see_their_assignment() {
        JsonNode listed = admin.get().uri("/v1/admin/platform-senders").exchange()
                .expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();
        JsonNode mine = find(listed, senderId);
        assertThat(mine.get("owner").asText()).isEqualTo("PLATFORM");
        assertThat(mine.get("tenantId").isNull()).isTrue();
        assertThat(mine.get("assignmentCount").asLong()).isEqualTo(2);

        // Workspace A sees the number with its own assignment, read-only.
        JsonNode aList = admin.get().uri("/v1/admin/tenants/{t}/whatsapp-senders", a).exchange()
                .expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();
        JsonNode aView = find(aList, senderId);
        assertThat(aView.get("owner").asText()).isEqualTo("PLATFORM");
        assertThat(aView.get("assignment").get("label").asText()).startsWith("Banca A");
        assertThat(aView.get("assignment").get("dailyOutboundCap").asInt()).isEqualTo(1000);
        admin.post().uri("/v1/admin/tenants/{t}/whatsapp-senders/{s}/suspend", a, senderId).exchange()
                .expectStatus().isForbidden().expectBody().jsonPath("$.error").isEqualTo("platform_sender");

        // Workspace C isn't assigned: the number doesn't exist for it.
        admin.get().uri("/v1/admin/tenants/{t}/whatsapp-senders/{s}", c, senderId).exchange()
                .expectStatus().isNotFound();
        JsonNode cList = admin.get().uri("/v1/admin/tenants/{t}/whatsapp-senders", c).exchange()
                .expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();
        assertThat(find(cList, senderId)).isNull();

        // Labels are unique on the number, whatever the case.
        String takenLabel = aView.get("assignment").get("label").asText().toUpperCase();
        admin.put().uri("/v1/admin/platform-senders/{s}/assignments/{t}", senderId, c)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new PlatformSenderDtos.AssignmentRequest(takenLabel, null))
                .exchange().expectStatus().isEqualTo(409).expectBody().jsonPath("$.error").isEqualTo("label_taken");

        // The number can't be removed while workspaces use it.
        admin.delete().uri("/v1/admin/platform-senders/{s}", senderId).exchange()
                .expectStatus().isEqualTo(409).expectBody().jsonPath("$.error").isEqualTo("sender_has_assignments");
    }

    @Test
    void ending_an_assignment_previews_then_closes_what_the_workspace_held() {
        putMembers(a, List.of(randomPhone(), randomPhone()));
        putKeywords(a, List.of(new WhatsAppKeywordActionDtos.ActionItem("TICKET XA", keywordUrl(), "ROUTED")));

        JsonNode preview = admin.delete().uri("/v1/admin/platform-senders/{s}/assignments/{t}?dryRun=true", senderId, a)
                .exchange().expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();
        assertThat(preview.get("dryRun").asBoolean()).isTrue();
        assertThat(preview.get("effects").get("membersDeleted").asLong()).isEqualTo(2);
        assertThat(preview.get("effects").get("keywordActionsDeleted").asInt()).isEqualTo(1);
        assertThat(getMembers(a)).hasSize(2);

        JsonNode done = admin.delete().uri("/v1/admin/platform-senders/{s}/assignments/{t}", senderId, a)
                .exchange().expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();
        assertThat(done.get("dryRun").asBoolean()).isFalse();
        assertThat(done.get("effects").get("membersDeleted").asLong()).isEqualTo(2);
        admin.get().uri("/v1/admin/tenants/{t}/whatsapp-senders/{s}/members", a, senderId).exchange()
                .expectStatus().isNotFound();
        // The prefix A held is free on the number again.
        putKeywords(b, List.of(new WhatsAppKeywordActionDtos.ActionItem("TICKET XA", keywordUrl(), "ROUTED")));
    }

    @Test
    void members_are_e164_phones_on_a_platform_sender_only() {
        String p1 = randomPhone();
        List<String> saved = putMembers(a, List.of(p1, p1));
        assertThat(saved).containsExactly(p1);
        admin.put().uri("/v1/admin/tenants/{t}/whatsapp-senders/{s}/members", a, senderId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new WhatsAppSenderDtos.MemberSet(List.of(senderPhone), null))
                .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.error").isEqualTo("invalid_phone");
        admin.put().uri("/v1/admin/tenants/{t}/whatsapp-senders/{s}/members/{p}", a, senderId, "8095551234")
                .exchange().expectStatus().isBadRequest();
        admin.delete().uri("/v1/admin/tenants/{t}/whatsapp-senders/{s}/members/{p}", a, senderId, p1)
                .exchange().expectStatus().isNoContent();
        assertThat(getMembers(a)).isEmpty();
    }

    @Test
    void a_routed_prefix_belongs_to_one_workspace_and_reply_prefixes_to_each() {
        putKeywords(a, List.of(new WhatsAppKeywordActionDtos.ActionItem("TICKET QA", keywordUrl(), "ROUTED"),
                new WhatsAppKeywordActionDtos.ActionItem("NO ES MIO", keywordUrl(), "REPLY")));
        admin.put().uri("/v1/admin/tenants/{t}/whatsapp-senders/{s}/keyword-actions", b, senderId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new WhatsAppKeywordActionDtos.ActionSet(List.of(
                        new WhatsAppKeywordActionDtos.ActionItem("ticket qa", keywordUrl(), null))))
                .exchange().expectStatus().isEqualTo(409).expectBody().jsonPath("$.error").isEqualTo("keyword_prefix_taken");
        putKeywords(b, List.of(new WhatsAppKeywordActionDtos.ActionItem("NO ES MIO", keywordUrl(), "REPLY")));
        admin.put().uri("/v1/admin/tenants/{t}/whatsapp-senders/{s}/keyword-actions", b, senderId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new WhatsAppKeywordActionDtos.ActionSet(List.of(
                        new WhatsAppKeywordActionDtos.ActionItem("Cambiar negocio", keywordUrl(), "REPLY"))))
                .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.error").isEqualTo("reserved_keyword");
    }

    @Test
    void a_binding_on_a_platform_sender_must_resolve_staff() {
        profile(a, "ops");
        admin.post().uri("/v1/admin/tenants/{t}/channel-bindings", a).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(binding(null, OnUnresolvedPolicy.REJECT))
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("platform_sender_requires_resolver");
        long resolver = resolver(a);
        admin.post().uri("/v1/admin/tenants/{t}/channel-bindings", a).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(binding(resolver, OnUnresolvedPolicy.AUTO_REPLY))
                .exchange().expectStatus().isBadRequest();
        admin.post().uri("/v1/admin/tenants/{t}/channel-bindings", a).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(binding(resolver, OnUnresolvedPolicy.REJECT))
                .exchange().expectStatus().isCreated();
        // An unassigned workspace can't bind the number at all.
        profile(c, "ops");
        admin.post().uri("/v1/admin/tenants/{t}/channel-bindings", c).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(binding(resolver(c), OnUnresolvedPolicy.REJECT))
                .exchange().expectStatus().isNotFound();
    }

    /* ------------------------------ routing ------------------------------ */

    @Test
    void a_routed_keyword_reaches_its_workspace_from_anyone() {
        putKeywords(a, List.of(new WhatsAppKeywordActionDtos.ActionItem("TICKET RA", keywordUrl(), "ROUTED")));
        INSTALL.stubFor(post(urlEqualTo("/integrations/keywords")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"reply\":\"Listo.\"}")));

        String sid = postInbound(randomPhone(), "Ticket RA 7G4K2");
        InboundMessageEntity row = processed(sid);

        assertThat(row.getTenantId()).isEqualTo(a);
        assertThat(row.getRouteReason()).isEqualTo("routed_keyword");
        assertThat(row.getProcessingError()).isNull();
        assertThat(INSTALL.findAll(postRequestedFor(urlEqualTo("/integrations/keywords"))
                .withRequestBody(matchingJsonPath("$.tenantId", equalTo(a))))).hasSize(1);
    }

    @Test
    void a_stranger_never_reaches_an_assistant_and_hears_the_fixed_line_once_a_day() {
        String stranger = randomPhone();
        String sid = postInbound(stranger, "hola, quiero jugar");
        InboundMessageEntity row = processed(sid);

        assertThat(row.getTenantId()).isNull();
        assertThat(row.getProcessingError()).isEqualTo("not_routed");
        assertThat(autoReplies.findBySenderIdAndEndUserPhone(senderId, stranger)).isPresent();

        // Quoting a message workspace B sent routes the reply to B — but a
        // non-member still gets no assistant there.
        String bMessage = "SM_out_" + UUID.randomUUID();
        outbound.save(new OutboundMessageEntity(bMessage, b, senderId, stranger, null, "Tu ticket"));
        String sid2 = postInbound(stranger, "gracias", Map.of("OriginalRepliedMessageSid", bMessage));
        InboundMessageEntity row2 = processed(sid2);
        assertThat(row2.getTenantId()).isEqualTo(b);
        assertThat(row2.getRouteReason()).isEqualTo("quoted_reply");
        assertThat(row2.getProcessingError()).isEqualTo("not_a_member");
    }

    @Test
    void staff_of_one_workspace_go_straight_to_it_and_its_resolver_hears_platform_sender() {
        String staff = randomPhone();
        putMembers(a, List.of(staff));
        profile(a, "ops");
        admin.post().uri("/v1/admin/tenants/{t}/channel-bindings", a).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(binding(resolver(a), OnUnresolvedPolicy.REJECT)).exchange().expectStatus().isCreated();
        INSTALL.stubFor(post(urlEqualTo("/yaya/resolve-staff")).willReturn(aResponse().withStatus(404)));

        InboundMessageEntity row = processed(postInbound(staff, "¿cuánto vendimos hoy?"));

        assertThat(row.getTenantId()).isEqualTo(a);
        assertThat(row.getRouteReason()).isEqualTo("member");
        assertThat(row.getProcessingError()).isEqualTo("auth_resolver_rejected");
        var calls = INSTALL.findAll(postRequestedFor(urlEqualTo("/yaya/resolve-staff")));
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).getHeader("X-Yaya-Tenant")).isEqualTo(a);
        assertThat(calls.get(0).getBodyAsString()).contains("\"platformSender\":true").contains("\"tenantId\":\"" + a + "\"");
    }

    @Test
    void staff_of_two_workspaces_are_asked_once_then_pinned_and_can_switch() {
        String owner = randomPhone();
        putMembers(a, List.of(owner));
        putMembers(b, List.of(owner));

        String first = postInbound(owner, "reporte de ventas");
        InboundMessageEntity asked = processed(first);
        assertThat(asked.getTenantId()).isNull();
        assertThat(asked.getProcessingError()).isEqualTo("awaiting_org_choice");
        assertThat(routes.find(senderId, owner).orElseThrow().getPendingChoice()).isNotBlank();

        // The answer names B (typed number 2 = second offered row: assignment order A, B).
        String answer = postInbound(owner, "2");
        InboundMessageEntity answered = processed(answer);
        assertThat(answered.getProcessingError()).isEqualTo("org_choice_answer");
        assertThat(answered.getTenantId()).isEqualTo(b);
        // The held message was replayed into B (no binding there: it stops at binding lookup).
        InboundMessageEntity replayed = processed(first);
        assertThat(replayed.getTenantId()).isEqualTo(b);
        assertThat(replayed.getRouteReason()).isEqualTo("pick");
        assertThat(replayed.getProcessingError()).isEqualTo("no_binding_matched");
        assertThat(routes.find(senderId, owner).orElseThrow().getActiveTenantId()).isEqualTo(b);

        // Free text now stays with B.
        InboundMessageEntity next = processed(postInbound(owner, "y ayer?"));
        assertThat(next.getTenantId()).isEqualTo(b);
        assertThat(next.getRouteReason()).isEqualTo("pinned");

        // A message from A since the last inbound unpins: A is the one who wrote.
        outbound.save(new OutboundMessageEntity("SM_out_" + UUID.randomUUID(), a, senderId, owner, null, "Solicitud UR-1"));
        InboundMessageEntity afterA = processed(postInbound(owner, "aprobado"));
        assertThat(afterA.getTenantId()).isEqualTo(a);
        assertThat(afterA.getRouteReason()).isEqualTo("last_writer");

        // CAMBIAR NEGOCIO asks again, holding nothing.
        InboundMessageEntity sw = processed(postInbound(owner, "Cambiar negocio"));
        assertThat(sw.getProcessingError()).isEqualTo("org_choice_asked");
    }

    @Test
    void a_paused_workspace_takes_no_inbound() {
        String staff = randomPhone();
        putMembers(a, List.of(staff));
        admin.post().uri("/v1/admin/platform-senders/{s}/assignments/{t}/pause", senderId, a).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("PAUSED");

        InboundMessageEntity row = processed(postInbound(staff, "hola"));
        assertThat(row.getTenantId()).isNull();
        assertThat(row.getProcessingError()).isEqualTo("not_routed");
    }

    /* --------------------- moves, templates, STOP, archive --------------------- */

    @Test
    void a_workspace_number_moves_to_the_platform_and_back_only_once_nobody_uses_it() {
        String phone = randomPhone();
        var own = admin.post().uri("/v1/admin/tenants/{t}/whatsapp-senders", c).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new WhatsAppSenderDtos.SenderRequest(phone, "Banca C",
                        new WhatsAppSenderDtos.BusinessProfileRequest("Banca C SRL", "Banca C", "ops@c.example",
                                "Address", "https://c.example", "RETAIL", "Tickets."), null))
                .exchange().expectStatus().isCreated()
                .expectBody(WhatsAppSenderDtos.SenderResponse.class).returnResult().getResponseBody();
        senderRows.findById(own.id()).ifPresent(e -> {
            e.setStatus("ACTIVE");
            e.setTwilioSenderSid("SR_MOVE_" + own.id());
            senderRows.save(e);
        });

        JsonNode moved = admin.post().uri("/v1/admin/tenants/{t}/whatsapp-senders/{s}/transfer", c, own.id())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new WhatsAppSenderDtos.TransferRequest(null, null, null, true, true))
                .exchange().expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();
        JsonNode dest = moved.get("destination");
        assertThat(dest.get("owner").asText()).isEqualTo("PLATFORM");
        assertThat(dest.get("tenantId").isNull()).isTrue();
        assertThat(dest.get("phoneE164").asText()).isEqualTo(phone);
        String platformId = dest.get("id").asText();

        // The shared number of this test is still assigned: it can't leave the platform.
        admin.post().uri("/v1/admin/platform-senders/{s}/transfer", senderId).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new PlatformSenderDtos.TransferToWorkspaceRequest(c, null, null, true))
                .exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.error").isEqualTo("sender_has_assignments");
        // The one just moved has no workspace yet: it can go back.
        JsonNode back = admin.post().uri("/v1/admin/platform-senders/{s}/transfer", platformId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new PlatformSenderDtos.TransferToWorkspaceRequest(c, null, null, true))
                .exchange().expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();
        assertThat(back.get("destination").get("tenantId").asText()).isEqualTo(c);
        assertThat(back.get("destination").get("owner").asText()).isEqualTo("WORKSPACE");
    }

    @Test
    void a_template_names_the_placeholder_the_platform_fills_with_the_business_name() {
        String name = "sl_ps_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        JsonNode declared = admin.put().uri("/v1/admin/tenants/{t}/whatsapp-senders/{s}/templates/by-name/{n}",
                        a, senderId, name)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new com.yayatechandinnovations.yayaagentic.api.dto.WhatsAppTemplateDtos.TemplateDeclaration(
                        name, "es", com.yayatechandinnovations.yayaagentic.channel.whatsapp.templates.TemplateCategory.UTILITY,
                        "NONE", "*{{1}}* · Hola {{2}}, tu ticket está listo.", List.of(),
                        new com.yayatechandinnovations.yayaagentic.api.dto.WhatsAppTemplateDtos.Samples(
                                List.of("Banca La Suerte", "Ana"), null), 1))
                .exchange().expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();
        assertThat(declared.get("businessLabelVar").asInt()).isEqualTo(1);

        admin.put().uri("/v1/admin/tenants/{t}/whatsapp-senders/{s}/templates/by-name/{n}", a, senderId, name + "_x")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new com.yayatechandinnovations.yayaagentic.api.dto.WhatsAppTemplateDtos.TemplateDeclaration(
                        name + "_x", "es", com.yayatechandinnovations.yayaagentic.channel.whatsapp.templates.TemplateCategory.UTILITY,
                        "NONE", "*{{1}}* · Hola {{2}}.", List.of(),
                        new com.yayatechandinnovations.yayaagentic.api.dto.WhatsAppTemplateDtos.Samples(
                                List.of("Banca", "Ana"), null), 3))
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("bad_business_label_var");

        // The console's create/edit routes carry it too; 0 clears it on edit.
        var cat = com.yayatechandinnovations.yayaagentic.channel.whatsapp.templates.TemplateCategory.UTILITY;
        JsonNode created = admin.post().uri("/v1/admin/tenants/{t}/whatsapp-senders/{s}/templates", a, senderId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new com.yayatechandinnovations.yayaagentic.api.dto.WhatsAppTemplateDtos.TemplateRequest(
                        name + "_c", "es", cat, "*{{1}}* · Tu código es {{2}}.", 1))
                .exchange().expectStatus().isCreated().expectBody(JsonNode.class).returnResult().getResponseBody();
        assertThat(created.get("businessLabelVar").asInt()).isEqualTo(1);
        JsonNode cleared = admin.put().uri("/v1/admin/tenants/{t}/whatsapp-senders/{s}/templates/{id}",
                        a, senderId, created.get("id").asLong())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new com.yayatechandinnovations.yayaagentic.api.dto.WhatsAppTemplateDtos.TemplateUpdate(
                        "*{{1}}* · Tu código es {{2}}.", cat, 0))
                .exchange().expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();
        assertThat(cleared.get("businessLabelVar").isNull()).isTrue();
    }

    @Test
    void a_stop_is_the_numbers_and_counts_against_the_workspace_that_wrote_last() {
        String phone = randomPhone();
        outbound.save(new OutboundMessageEntity("SM_out_" + UUID.randomUUID(), a, senderId, phone, null, "Tu ticket"));

        processed(postInbound(phone, "STOP"));

        var stop = optOutRows.findBySenderIdAndEndUserPhone(senderId, phone).orElseThrow();
        assertThat(stop.getAttributedTenantId()).isEqualTo(a);
        JsonNode list = admin.get().uri("/v1/admin/platform-senders/{s}/assignments", senderId).exchange()
                .expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();
        for (JsonNode row : list) {
            long stops = row.get("stats").get("optOuts7d").asLong();
            assertThat(stops).isEqualTo(a.equals(row.get("tenantId").asText()) ? 1 : 0);
        }
    }

    @Test
    void every_free_form_line_on_the_number_names_its_business_once() {
        var sender = senderService.requireForTenant(null,
                new com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId(senderId));
        var tenantA = new com.yayatechandinnovations.yayaagentic.core.Ids.TenantId(a);
        String labelled = senderService.labelled(tenantA, sender, "Tu ticket está listo.");
        assertThat(labelled).startsWith("*Banca A").endsWith("\nTu ticket está listo.");
        assertThat(senderService.labelled(tenantA, sender, labelled)).isEqualTo(labelled);
        // The platform's own lines and unassigned workspaces go out as they are.
        assertThat(senderService.labelled(null, sender, "hola")).isEqualTo("hola");
        assertThat(senderService.labelled(new com.yayatechandinnovations.yayaagentic.core.Ids.TenantId(c), sender,
                "hola")).isEqualTo("hola");
    }

    @Test
    void archiving_a_workspace_ends_its_assignments() {
        assign(c, "Banca C " + UUID.randomUUID().toString().substring(0, 4));
        putMembers(c, List.of(randomPhone()));

        admin.post().uri("/v1/admin/tenants/{t}/archive", c).exchange().expectStatus().isOk();

        JsonNode list = admin.get().uri("/v1/admin/platform-senders/{s}/assignments", senderId).exchange()
                .expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();
        for (JsonNode row : list) assertThat(row.get("tenantId").asText()).isNotEqualTo(c);
    }

    /* ------------------------------ helpers ------------------------------ */

    private String tenant(String id) {
        admin.post().uri("/v1/admin/tenants").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new AdminDtos.TenantRequest(id, id, INSTALL.baseUrl(), List.of(), List.of(), false,
                        null, null, Map.of()))
                .exchange().expectStatus().value(s -> assertThat(s).isIn(201, 409));
        admin.put().uri("/v1/admin/tenants/{t}/service-token-secret", id).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new TenantServiceTokenSecretController.SetRequest(SERVICE_SECRET))
                .exchange().expectStatus().is2xxSuccessful();
        return id;
    }

    private String createPlatformSender(String phone) {
        JsonNode created = admin.post().uri("/v1/admin/platform-senders").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new PlatformSenderDtos.PlatformSenderRequest(phone, "Avisos Smart Lottery",
                        new WhatsAppSenderDtos.BusinessProfileRequest("Smart Lottery SRL", "Avisos Smart Lottery",
                                "ops@sl.example", "Address", "https://sl.example", "RETAIL", "Avisos."),
                        "es-DO", null))
                .exchange().expectStatus().isCreated().expectBody(JsonNode.class).returnResult().getResponseBody();
        String id = created.get("id").asText();
        assertThat(created.get("owner").asText()).isEqualTo("PLATFORM");
        // Tests flip the row ACTIVE directly; the service path would call Twilio.
        senderRows.findById(id).ifPresent(e -> {
            e.setStatus("ACTIVE");
            e.setTwilioSenderSid("SR_PS_" + id);
            senderRows.save(e);
        });
        return id;
    }

    private void assign(String tenant, String label) {
        admin.put().uri("/v1/admin/platform-senders/{s}/assignments/{t}", senderId, tenant)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new PlatformSenderDtos.AssignmentRequest(label, null))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("ACTIVE");
    }

    private List<String> putMembers(String tenant, List<String> phones) {
        return admin.put().uri("/v1/admin/tenants/{t}/whatsapp-senders/{s}/members", tenant, senderId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new WhatsAppSenderDtos.MemberSet(phones, null))
                .exchange().expectStatus().isOk()
                .expectBody(WhatsAppSenderDtos.MemberSet.class).returnResult().getResponseBody().phones();
    }

    private List<String> getMembers(String tenant) {
        return admin.get().uri("/v1/admin/tenants/{t}/whatsapp-senders/{s}/members", tenant, senderId)
                .exchange().expectStatus().isOk()
                .expectBody(WhatsAppSenderDtos.MemberSet.class).returnResult().getResponseBody().phones();
    }

    private void putKeywords(String tenant, List<WhatsAppKeywordActionDtos.ActionItem> items) {
        admin.put().uri("/v1/admin/tenants/{t}/whatsapp-senders/{s}/keyword-actions", tenant, senderId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new WhatsAppKeywordActionDtos.ActionSet(items))
                .exchange().expectStatus().isOk();
    }

    private String keywordUrl() {
        return INSTALL.baseUrl() + "/integrations/keywords";
    }

    private void profile(String tenant, String pid) {
        admin.post().uri("/v1/admin/profiles").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new AdminDtos.ProfileRequest(tenant, pid, pid, "intro", "prompt", List.of(), null, null,
                        Map.of()))
                .exchange().expectStatus().value(s -> assertThat(s).isIn(201, 409));
    }

    private long resolver(String tenant) {
        var resp = admin.post().uri("/v1/admin/tenants/{t}/auth-resolvers", tenant)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new TenantAuthResolverController.ResolverRequest(
                        "staff-" + UUID.randomUUID().toString().substring(0, 8), "staff resolver", "HTTP", null,
                        "/yaya/resolve-staff", "NONE", null, null, null, 2500, false, null, 60, 3600,
                        "REJECT", "ACTIVE"))
                .exchange().expectStatus().isCreated()
                .expectBody(TenantAuthResolverController.ResolverResponse.class).returnResult().getResponseBody();
        return resp.id();
    }

    private ChannelBindingDtos.BindingRequest binding(Long resolverId, OnUnresolvedPolicy onUnresolved) {
        return new ChannelBindingDtos.BindingRequest(
                new ChannelBindingDtos.EndpointRequest("WHATSAPP_SENDER", senderId),
                "ops", 1, new RoutingRule.MatchAny(), SessionPolicy.defaults(), 100,
                BindingStatus.ACTIVE, resolverId, onUnresolved, null, null,
                onUnresolved == OnUnresolvedPolicy.AUTO_REPLY ? "Hola" : null);
    }

    private InboundMessageEntity processed(String sid) {
        await().atMost(Duration.ofSeconds(20))
                .until(() -> inbound.findById(sid).map(e -> e.getProcessedAt() != null).orElse(false));
        return inbound.findById(sid).orElseThrow();
    }

    private String postInbound(String phone, String body) {
        return postInbound(phone, body, Map.of());
    }

    private String postInbound(String phone, String body, Map<String, String> extra) {
        String sid = "SM_ps_" + UUID.randomUUID();
        Map<String, String> p = new LinkedHashMap<>();
        p.put("MessageSid", sid);
        p.put("From", "whatsapp:" + phone);
        p.put("To", "whatsapp:" + senderPhone);
        p.put("Body", body);
        p.put("NumMedia", "0");
        p.putAll(extra);
        String fullUrl = webhookBase + "/v1/channels/whatsapp/twilio/webhook/" + senderId;
        String sig = TwilioSignatureVerifier.computeExpectedSignature(fullUrl, p, authToken);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        p.forEach(form::add);
        base.post().uri("/v1/channels/whatsapp/twilio/webhook/{sid}", senderId)
                .header("X-Twilio-Signature", sig)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue(form)
                .exchange().expectStatus().isOk();
        // Sequential inbounds of one phone need distinct received_at for the router's "since the last one".
        try { Thread.sleep(15); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        return sid;
    }

    private static JsonNode find(JsonNode list, String id) {
        for (JsonNode n : list) if (id.equals(n.get("id").asText())) return n;
        return null;
    }

    private static String randomPhone() {
        return "+1555" + (2000000 + new Random().nextInt(7999999));
    }
}
