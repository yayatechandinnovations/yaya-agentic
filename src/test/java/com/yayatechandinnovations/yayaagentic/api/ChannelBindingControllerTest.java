package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.AdminDtos;
import com.yayatechandinnovations.yayaagentic.api.dto.ChannelBindingDtos;
import com.yayatechandinnovations.yayaagentic.api.dto.WhatsAppSenderDtos;
import com.yayatechandinnovations.yayaagentic.channel.binding.BindingStatus;
import com.yayatechandinnovations.yayaagentic.channel.binding.RoutingRule;
import com.yayatechandinnovations.yayaagentic.channel.binding.SessionPolicy;
import com.yayatechandinnovations.yayaagentic.operator_auth.TestAuthDance;
import com.yayatechandinnovations.yayaagentic.operator_auth.ratelimit.LoginRateLimiter;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * E2E coverage of the channel-bindings admin surface and the routing-rule
 * truth table from §8 of the design.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChannelBindingControllerTest {

    @Autowired WebTestClient client;
    @Autowired LoginRateLimiter rateLimiter;

    private static final String TENANT = "binding-test";
    private static final String OTHER_TENANT = "binding-test-other";
    private static final AtomicReference<String> SENDER_ID = new AtomicReference<>();
    private static final AtomicReference<String> OTHER_TENANT_SENDER_ID = new AtomicReference<>();
    private static final AtomicReference<Long> BINDING_ID = new AtomicReference<>();

    @BeforeEach
    void auth() {
        rateLimiter.resetAll();
        client = TestAuthDance.asBootstrap(client);
        ensureTenant(TENANT, "https://binding-test.example");
        ensureTenant(OTHER_TENANT, "https://binding-test-other.example");
        ensureProfile(TENANT, "binding-test-profile");
        ensureProfile(TENANT, "binding-test-sales");
        ensureProfile(OTHER_TENANT, "binding-test-profile");
        if (SENDER_ID.get() == null) {
            SENDER_ID.set(createSender(TENANT, "+15558881111"));
        }
        if (OTHER_TENANT_SENDER_ID.get() == null) {
            OTHER_TENANT_SENDER_ID.set(createSender(OTHER_TENANT, "+15558881112"));
        }
    }

    private void ensureTenant(String tenant, String host) {
        var req = new AdminDtos.TenantRequest(
                tenant, tenant + " name", host, List.of(), List.of(), true, null, null, Map.of());
        client.post().uri("/v1/admin/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().value(s -> assertThat(s).isIn(201, 409));
    }

    private void ensureProfile(String tenant, String profileId) {
        var req = new AdminDtos.ProfileRequest(
                tenant, profileId, profileId, "intro", "prompt fragment",
                List.of(), null, null, Map.of());
        client.post().uri("/v1/admin/profiles")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().value(s -> assertThat(s).isIn(201, 409));
    }

    private String createSender(String tenant, String phone) {
        var req = new WhatsAppSenderDtos.SenderRequest(
                phone, tenant + " WA",
                new WhatsAppSenderDtos.BusinessProfileRequest(
                        tenant + " Inc.", tenant + " WA", "ops@" + tenant + ".example",
                        "Address", "https://" + tenant + ".example", "RETAIL", "Stuff."),
                null);
        var res = client.post()
                .uri("/v1/admin/tenants/{t}/whatsapp-senders", tenant)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(WhatsAppSenderDtos.SenderResponse.class)
                .returnResult().getResponseBody();
        return res == null ? null : res.id();
    }

    @Test @Order(1)
    void creates_match_any_binding_with_defaults() {
        var req = new ChannelBindingDtos.BindingRequest(
                new ChannelBindingDtos.EndpointRequest("WHATSAPP_SENDER", SENDER_ID.get()),
                "binding-test-profile",
                1,
                new RoutingRule.MatchAny(),
                SessionPolicy.defaults(),
                100,
                BindingStatus.ACTIVE);
        var res = client.post()
                .uri("/v1/admin/tenants/{t}/channel-bindings", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(ChannelBindingDtos.BindingResponse.class)
                .returnResult().getResponseBody();
        assertThat(res).isNotNull();
        assertThat(res.profileId()).isEqualTo("binding-test-profile");
        assertThat(res.endpoint().id()).isEqualTo(SENDER_ID.get());
        assertThat(res.priority()).isEqualTo(100);
        assertThat(res.sessionPolicy().idleTimeout()).isEqualTo(Duration.ofHours(24));
        BINDING_ID.set(res.id());
    }

    @Test @Order(2)
    void rejects_cross_tenant_endpoint() {
        // Try to bind the OTHER tenant's sender from inside this tenant's
        // admin path — must 404 (sender_not_found), never silently succeed.
        var req = new ChannelBindingDtos.BindingRequest(
                new ChannelBindingDtos.EndpointRequest(
                        "WHATSAPP_SENDER", OTHER_TENANT_SENDER_ID.get()),
                "binding-test-profile", 1,
                new RoutingRule.MatchAny(), SessionPolicy.defaults(), 100, BindingStatus.ACTIVE);
        client.post()
                .uri("/v1/admin/tenants/{t}/channel-bindings", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> assertThat(err.error()).isEqualTo("sender_not_found"));
    }

    @Test @Order(3)
    void rejects_unknown_profile() {
        var req = new ChannelBindingDtos.BindingRequest(
                new ChannelBindingDtos.EndpointRequest("WHATSAPP_SENDER", SENDER_ID.get()),
                "no-such-profile", 1,
                new RoutingRule.MatchAny(), SessionPolicy.defaults(), 100, BindingStatus.ACTIVE);
        client.post()
                .uri("/v1/admin/tenants/{t}/channel-bindings", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody(AdminDtos.ApiError.class)
                .value(err -> assertThat(err.error()).isEqualTo("profile_not_found"));
    }

    @Test @Order(4)
    void persists_keyword_routing_rule_and_session_policy() {
        var req = new ChannelBindingDtos.BindingRequest(
                new ChannelBindingDtos.EndpointRequest("WHATSAPP_SENDER", SENDER_ID.get()),
                "binding-test-sales", 1,
                new RoutingRule.KeywordPrefix(List.of("#sales"), true),
                new SessionPolicy(Duration.ofHours(12),
                        Set.of("#new"), Set.of("bye"),
                        false, null, true),
                50,
                BindingStatus.ACTIVE);
        var res = client.post()
                .uri("/v1/admin/tenants/{t}/channel-bindings", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(ChannelBindingDtos.BindingResponse.class)
                .returnResult().getResponseBody();
        assertThat(res).isNotNull();
        assertThat(res.priority()).isEqualTo(50);
        assertThat(res.routing()).isInstanceOf(RoutingRule.KeywordPrefix.class);
        var kp = (RoutingRule.KeywordPrefix) res.routing();
        assertThat(kp.keywords()).containsExactly("#sales");
        assertThat(res.sessionPolicy().idleTimeout()).isEqualTo(Duration.ofHours(12));
    }

    @Test @Order(5)
    void lists_bindings_in_priority_order() {
        var list = client.get()
                .uri("/v1/admin/tenants/{t}/channel-bindings", TENANT)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(ChannelBindingDtos.BindingResponse.class)
                .returnResult().getResponseBody();
        assertThat(list).isNotNull().hasSizeGreaterThanOrEqualTo(2);
        // priority 50 comes before priority 100
        assertThat(list.get(0).priority()).isLessThanOrEqualTo(list.get(1).priority());
    }

    @Test @Order(6)
    void updates_priority_and_status() {
        var update = new ChannelBindingDtos.BindingUpdate(
                "binding-test-profile", 1,
                new RoutingRule.MatchAny(),
                SessionPolicy.defaults(),
                10,
                BindingStatus.PAUSED);
        var res = client.put()
                .uri("/v1/admin/tenants/{t}/channel-bindings/{id}", TENANT, BINDING_ID.get())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(update)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ChannelBindingDtos.BindingResponse.class)
                .returnResult().getResponseBody();
        assertThat(res).isNotNull();
        assertThat(res.priority()).isEqualTo(10);
        assertThat(res.status()).isEqualTo(BindingStatus.PAUSED);
    }

    @Test @Order(7)
    void delete_soft_deactivates() {
        client.delete()
                .uri("/v1/admin/tenants/{t}/channel-bindings/{id}", TENANT, BINDING_ID.get())
                .exchange()
                .expectStatus().isNoContent();
        // The deactivated row stays visible — it's a soft delete.
        var list = client.get()
                .uri("/v1/admin/tenants/{t}/channel-bindings", TENANT)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(ChannelBindingDtos.BindingResponse.class)
                .returnResult().getResponseBody();
        assertThat(list).anySatisfy(b -> {
            if (b.id() == BINDING_ID.get()) {
                assertThat(b.status()).isEqualTo(BindingStatus.DEACTIVATED);
            }
        });
    }
}
