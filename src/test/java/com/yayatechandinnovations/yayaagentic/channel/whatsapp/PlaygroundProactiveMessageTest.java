package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.yayatechandinnovations.yayaagentic.api.PlaygroundWhatsAppController;
import com.yayatechandinnovations.yayaagentic.api.dto.AdminDtos;
import com.yayatechandinnovations.yayaagentic.channel.system.Correlation;
import com.yayatechandinnovations.yayaagentic.channel.system.DeliveryRequest;
import com.yayatechandinnovations.yayaagentic.channel.system.SystemInboundMessage;
import com.yayatechandinnovations.yayaagentic.channel.system.SystemSender;
import com.yayatechandinnovations.yayaagentic.channel.system.SystemSenderService;
import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.operator_auth.TestAuthDance;
import com.yayatechandinnovations.yayaagentic.operator_auth.ratelimit.LoginRateLimiter;
import com.yayatechandinnovations.yayaagentic.outreach.ActionItem;
import com.yayatechandinnovations.yayaagentic.outreach.OutreachService;
import com.yayatechandinnovations.yayaagentic.persistence.ChatEntryPointEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChatEntryPointRepository;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * The Playground number is a real number for proactive messages: a system
 * sender linked to the tenant's simulator delivers into the Playground
 * conversation of the recipient phone — body and buttons — and a tap on a
 * bound button in the Playground runs its tool exactly as on a real number.
 * A phone with no Playground conversation gets nothing, and says so.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
class PlaygroundProactiveMessageTest {

    private static final String TENANT = "playground-outreach";
    private static final String PROFILE = "playground-outreach-profile";
    private static final String TOOL = "pg_accept_fare";

    @Autowired WebTestClient client;
    @Autowired LoginRateLimiter rateLimiter;
    @Autowired SystemSenderService systemSenders;
    @Autowired ChatEntryPointRepository entryPoints;
    @Autowired OutreachService outreach;
    @Autowired AcceptFareTool acceptFare;

    private SystemSender sender;

    @BeforeEach
    void setUp() {
        rateLimiter.resetAll();
        client = TestAuthDance.asBootstrap(client);
        client.post().uri("/v1/admin/tenants").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new AdminDtos.TenantRequest(TENANT, "Playground outreach", "https://pg-outreach.example",
                        List.of(), List.of(), true, null, null, Map.of()))
                .exchange().expectStatus().value(s -> assertThat(s).isIn(201, 409));
        client.post().uri("/v1/admin/tools").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new AdminDtos.ToolRequest(TENANT, TOOL,
                        "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}",
                        "{\"type\":\"object\"}", Map.of(),
                        new AdminDtos.ToolHandlerDto("BEAN", "playgroundAcceptFareTool", null),
                        Map.of("preconfirmableByAction", true)))
                .exchange().expectStatus().value(s -> assertThat(s).isIn(201, 409));
        client.post().uri("/v1/admin/capabilities").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new AdminDtos.CapabilityRequest(TENANT, "pg-fares", "Fares", "Accept a fare",
                        "Accept a fare proposal when asked.", List.of(TOOL), List.of()))
                .exchange().expectStatus().value(s -> assertThat(s).isIn(201, 409));
        client.post().uri("/v1/admin/profiles").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new AdminDtos.ProfileRequest(TENANT, PROFILE, "Playground outreach",
                        "Hola", "You help with rides.", List.of("pg-fares"), null, "es", Map.of()))
                .exchange().expectStatus().value(s -> assertThat(s).isIn(201, 409));
        if (entryPoints.findByTenantIdAndId(TENANT, "pg_ep").isEmpty()) {
            entryPoints.save(new ChatEntryPointEntity("pg_ep", TENANT, "customers", PROFILE, 1, "Customers",
                    "https://pg-outreach.example/logo.png", "{}", "ACTIVE"));
        }
        if (sender == null) {
            sender = systemSenders.create(new Ids.TenantId(TENANT), new SystemSenderService.Command(
                    "pg_ep", "Rides", true, null, null, null, null, null, null,
                    "snd_playground_sim__" + TENANT));
        }
        acceptFare.calls.clear();
    }

    @Test
    void a_proactive_message_lands_in_the_phones_playground_conversation_and_its_button_runs_the_tool() {
        String phone = "+1555" + (1000000 + new java.util.Random().nextInt(8999999));
        String conversation = "wa-" + UUID.randomUUID();
        inbound(Map.of("text", "hola", "fromPhone", phone, "sessionId", conversation,
                "profileId", PROFILE, "profileVersion", 1));
        await().atMost(Duration.ofSeconds(20)).until(() -> drain(conversation).messages().size() >= 1);
        int before = drain(conversation).messages().size();

        var receipt = outreach.ingest(message(phone, "ride_1:fare:1"), auth());

        assertThat(receipt.disposition()).isEqualTo(OutreachService.Disposition.DELIVERED);
        assertThat(receipt.deliveries()).singleElement().satisfies(d -> {
            assertThat(d.channel()).isEqualTo("WHATSAPP");
            assertThat(d.status()).isEqualTo("SENT");
            assertThat(d.fallbackReason()).isNull();
            assertThat(d.providerMessageId()).startsWith("playground:");
        });
        var messages = drain(conversation).messages();
        assertThat(messages).hasSize(before + 1);
        var proactive = messages.get(messages.size() - 1);
        assertThat(proactive.body()).startsWith("Taxi Caribe propone RD$450.").contains("1. Aceptar");
        assertThat(proactive.interactive().kind()).isEqualTo("buttons");
        assertThat(proactive.interactive().options()).extracting(o -> o.get("label"))
                .containsExactly("Aceptar", "Hablar con despacho");
        String accept = String.valueOf(proactive.interactive().options().get(0).get("id"));
        assertThat(accept).startsWith("act:");
        assertThat(receipt.deliveries().get(0).providerMessageId()).isEqualTo("playground:" + proactive.id());

        // The customer taps «Aceptar» in the Playground: the bound tool runs with its args.
        inbound(Map.of("text", "Aceptar", "interactiveId", accept, "fromPhone", phone, "sessionId", conversation,
                "profileId", PROFILE, "profileVersion", 1));
        await().atMost(Duration.ofSeconds(20)).until(() -> !acceptFare.calls.isEmpty());
        assertThat(acceptFare.calls).singleElement().satisfies(call -> {
            assertThat(call.get("id")).isEqualTo("fp_1");
            assertThat(call.get("_idempotencyKey")).isEqualTo(accept);
        });
    }

    @Test
    void a_phone_without_a_playground_conversation_gets_nothing_and_the_receipt_says_why() {
        String phone = "+1555" + (1000000 + new java.util.Random().nextInt(8999999));

        var receipt = outreach.ingest(message(phone, "ride_2:fare:" + UUID.randomUUID()), auth());

        assertThat(receipt.disposition()).isEqualTo(OutreachService.Disposition.DROPPED);
        assertThat(receipt.deliveries()).singleElement().satisfies(d -> {
            assertThat(d.channel()).isEqualTo("WHATSAPP");
            assertThat(d.status()).isEqualTo("FAILED");
            assertThat(d.fallbackReason()).isEqualTo("no_playground_session");
        });
        assertThat(acceptFare.calls).isEmpty();
    }

    /* ------------------------------ helpers ------------------------------ */

    private SystemInboundMessage message(String phone, String dedupKey) {
        return new SystemInboundMessage(new Ids.TenantId(TENANT), sender.id(), null,
                "Taxi Caribe propone RD$450. ¿Lo aceptas?", List.of(),
                new Correlation("yaya-go", "ride_1", "ride.fare_proposed"), dedupKey, Map.of(),
                List.of(new ActionItem("Aceptar", null, "primary",
                                new ActionItem.BoundTool(TOOL, Map.of("id", "fp_1")), null, null),
                        new ActionItem("Hablar con despacho", "hablar con despacho", "neutral")),
                new DeliveryRequest(DeliveryRequest.Channel.WHATSAPP, phone,
                        new DeliveryRequest.TemplateRef("yg_fare_proposal_es", "es", List.of("Taxi Caribe")),
                        DeliveryRequest.Fallback.NONE));
    }

    private static OutreachService.OutreachAuth auth() {
        return new OutreachService.OutreachAuth(new Principal("yaya-go", new Ids.TenantId(TENANT), Set.of(),
                Map.of(), Instant.now()));
    }

    private void inbound(Map<String, Object> body) {
        client.post().uri("/v1/admin/tenants/{t}/whatsapp-playground/inbound", TENANT)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isAccepted();
    }

    private PlaygroundWhatsAppController.OutboundDrain drain(String conversation) {
        return client.get().uri("/v1/admin/tenants/{t}/whatsapp-playground/outbound/{s}", TENANT, conversation)
                .exchange().expectStatus().isOk()
                .expectBody(PlaygroundWhatsAppController.OutboundDrain.class)
                .returnResult().getResponseBody();
    }

    /** Stands in for yg_accept_fare_proposal: records each call's args and its idempotency key. */
    public static class AcceptFareTool implements ToolHandler<Map<String, Object>, Map<String, Object>> {
        final List<Map<String, Object>> calls = new CopyOnWriteArrayList<>();

        @Override
        public Map<String, Object> execute(Map<String, Object> input, ExecutionContext ctx) {
            Map<String, Object> call = new java.util.LinkedHashMap<>(input == null ? Map.of() : input);
            call.put("_idempotencyKey", String.valueOf(ctx.attributes().get("idempotencyKey")));
            calls.add(call);
            return Map.of("accepted", true);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean("playgroundAcceptFareTool")
        AcceptFareTool playgroundAcceptFareTool() {
            return new AcceptFareTool();
        }
    }
}
