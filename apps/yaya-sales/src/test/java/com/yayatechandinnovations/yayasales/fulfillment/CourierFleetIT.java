package com.yayatechandinnovations.yayasales.fulfillment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.TenantRef;
import com.yayatechandinnovations.yayasales.stores.StorePolicies;
import com.yayatechandinnovations.yayasales.stores.StoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §8.9/§10.4/§14 — courier trust &amp; the KYC gate over HTTP: registration
 * only through a signed, unexpired, SINGLE-USE invite; phone OTP-verified;
 * documents encrypted and merchant-reviewed; approval requires the documents
 * and mints the operational credential (rotating the registration token); a
 * non-APPROVED agent can read its own status but do nothing operational.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CourierFleetIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("yaya_sales");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("yaya.sales.secrets.encryption-key",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
        r.add("yaya.sales.outbox.poll-enabled", () -> "false");
        r.add("yaya.sales.inventory.sweep-enabled", () -> "false");
        r.add("yaya.sales.delivery.otp-echo-enabled", () -> "true");   // dev/test echo (documented)
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DeliveryFleet fleet;
    @Autowired StoreService storeService;

    StoreId store;

    @BeforeEach
    void storefront() {
        store = storeService.create(new TenantRef("tenant-fleet"), "Fleet Store", "DOP",
                StorePolicies.defaults()).id();
    }

    // ── the invite gate (§14: PENDING only from a signed, unexpired invite) ──

    @Test
    void registrationWithoutAValidInviteIsRejected() throws Exception {
        // Forged token, expired invite, and burnt (already used) invite all
        // answer the same 422 — no probing which failure it was.
        mvc.perform(register("no-such-invite", "+18095550100"))
                .andExpect(status().isUnprocessableEntity());

        String expired = fleet.mintInvite(store, "op:test", Duration.ofHours(-1)).token();
        mvc.perform(register(expired, "+18095550101"))
                .andExpect(status().isUnprocessableEntity());

        String single = fleet.mintInvite(store, "op:test", null).token();
        mvc.perform(register(single, "+18095550102")).andExpect(status().isCreated());
        mvc.perform(register(single, "+18095550103"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void adminMintsInvitesOverHttp() throws Exception {
        MvcResult result = mvc.perform(admin(post("/v1/sales/admin/delivery/agents/invites"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ttlHours\":24}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode invite = json.readTree(result.getResponse().getContentAsString());
        assertThat(invite.get("inviteToken").asText()).isNotBlank();

        mvc.perform(register(invite.get("inviteToken").asText(), "+18095550110"))
                .andExpect(status().isCreated());
    }

    // ── OTP + the pre-approval surface ───────────────────────────────────────

    @Test
    void wrongOtpIsRejectedAndTheRightOneMintsARegistrationToken() throws Exception {
        JsonNode registered = registered("+18095550120");
        mvc.perform(verify("+18095550120", "000000"))
                .andExpect(status().isUnprocessableEntity());

        JsonNode credential = verified("+18095550120", registered.get("otp").asText());
        String token = credential.get("courierToken").asText();

        JsonNode me = getJson(bearer(get("/v1/sales/delivery/me"), token));
        assertThat(me.get("status").asText()).isEqualTo("PENDING");
        assertThat(me.get("phoneVerified").asBoolean()).isTrue();
    }

    @Test
    void courierEndpointsRejectMissingAndUnknownTokens() throws Exception {
        mvc.perform(get("/v1/sales/delivery/me")).andExpect(status().isUnauthorized());
        mvc.perform(bearer(get("/v1/sales/delivery/me"), "not-a-real-token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(bearer(get("/v1/sales/delivery/assignments"), "not-a-real-token"))
                .andExpect(status().isUnauthorized());
    }

    // ── the §14 KYC gate ─────────────────────────────────────────────────────

    @Test
    void aNonApprovedAgentCannotListAssignmentsOrCollectCod() throws Exception {
        Courier courier = registeredAndVerified("+18095550130");

        // PENDING (verified, no docs): status readable, operations forbidden.
        mvc.perform(bearer(get("/v1/sales/delivery/assignments"), courier.token()))
                .andExpect(status().isForbidden());
        mvc.perform(bearer(post("/v1/sales/delivery/assignments/any-id/collect-cod"), courier.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amountMinor\":1}"))
                .andExpect(status().isForbidden());

        // DOCS_SUBMITTED / UNDER_REVIEW: still forbidden.
        uploadDocuments(courier, "PHOTO_ID_FRONT", "VEHICLE_REGISTRATION");
        mvc.perform(bearer(get("/v1/sales/delivery/assignments"), courier.token()))
                .andExpect(status().isForbidden());

        // REJECTED: can still read its own status (+ reason), nothing else.
        mvc.perform(admin(post("/v1/sales/admin/delivery/agents/" + courier.agentId() + "/reject"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"documents unreadable\"}"))
                .andExpect(status().isOk());
        JsonNode me = getJson(bearer(get("/v1/sales/delivery/me"), courier.token()));
        assertThat(me.get("status").asText()).isEqualTo("REJECTED");
        mvc.perform(bearer(get("/v1/sales/delivery/assignments"), courier.token()))
                .andExpect(status().isForbidden());
    }

    @Test
    void approvalWithoutTheRequiredDocumentsIs422() throws Exception {
        Courier courier = registeredAndVerified("+18095550140");

        // No documents at all.
        mvc.perform(admin(post("/v1/sales/admin/delivery/agents/" + courier.agentId() + "/approve")))
                .andExpect(status().isUnprocessableEntity());

        // A photo ID alone is not enough — a vehicle document is required too.
        uploadDocuments(courier, "PHOTO_ID_FRONT");
        mvc.perform(admin(post("/v1/sales/admin/delivery/agents/" + courier.agentId() + "/approve")))
                .andExpect(status().isUnprocessableEntity());

        uploadDocuments(courier, "INSURANCE");
        MvcResult approved = mvc.perform(admin(
                        post("/v1/sales/admin/delivery/agents/" + courier.agentId() + "/approve")))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(approved.getResponse().getContentAsString())
                .get("courierToken").asText()).isNotBlank();
    }

    @Test
    void approvalRotatesTheRegistrationTokenToTheOperationalCredential() throws Exception {
        Courier courier = registeredAndVerified("+18095550150");
        uploadDocuments(courier, "DRIVER_LICENSE", "VEHICLE_REGISTRATION");

        MvcResult approved = mvc.perform(admin(
                        post("/v1/sales/admin/delivery/agents/" + courier.agentId() + "/approve")))
                .andExpect(status().isOk()).andReturn();
        String operational = json.readTree(approved.getResponse().getContentAsString())
                .get("courierToken").asText();

        // The registration token died with the rotation; the operational one works.
        mvc.perform(bearer(get("/v1/sales/delivery/me"), courier.token()))
                .andExpect(status().isUnauthorized());
        JsonNode me = getJson(bearer(get("/v1/sales/delivery/me"), operational));
        assertThat(me.get("status").asText()).isEqualTo("APPROVED");
        JsonNode assignments = getJson(bearer(get("/v1/sales/delivery/assignments"), operational));
        assertThat(assignments).isEmpty();   // approved, nothing assigned yet

        // Suspension shuts the operational surface again.
        mvc.perform(admin(post("/v1/sales/admin/delivery/agents/" + courier.agentId() + "/suspend"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"audit\"}"))
                .andExpect(status().isOk());
        mvc.perform(bearer(get("/v1/sales/delivery/assignments"), operational))
                .andExpect(status().isForbidden());
    }

    // ── documents are encrypted PII, reviewed on demand (§15) ───────────────

    @Test
    void documentListingsCarryMetadataOnlyAndContentDecryptsOnDemand() throws Exception {
        Courier courier = registeredAndVerified("+18095550160");
        String content = Base64.getEncoder().encodeToString("front-of-id".getBytes());
        mvc.perform(bearer(post("/v1/sales/delivery/agents/" + courier.agentId() + "/documents"),
                        courier.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documents\":[{\"kind\":\"PHOTO_ID_FRONT\",\"contentBase64\":\""
                                + content + "\"}]}"))
                .andExpect(status().isOk());

        JsonNode agent = getJson(admin(get("/v1/sales/admin/delivery/agents/" + courier.agentId())));
        JsonNode document = agent.get("documents").get(0);
        assertThat(document.get("kind").asText()).isEqualTo("PHOTO_ID_FRONT");
        assertThat(document.toString()).doesNotContain(content);   // never raw in listings

        JsonNode decrypted = getJson(admin(get("/v1/sales/admin/delivery/agents/"
                + courier.agentId() + "/documents/" + document.get("id").asText() + "/content")));
        assertThat(decrypted.get("contentBase64").asText()).isEqualTo(content);

        // A courier cannot feed another agent's file.
        Courier other = registeredAndVerified("+18095550161");
        mvc.perform(bearer(post("/v1/sales/delivery/agents/" + courier.agentId() + "/documents"),
                        other.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documents\":[{\"kind\":\"OTHER\",\"contentBase64\":\"x\"}]}"))
                .andExpect(status().isNotFound());
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    record Courier(String agentId, String token) {}

    private Courier registeredAndVerified(String phone) throws Exception {
        JsonNode registered = registered(phone);
        JsonNode credential = verified(phone, registered.get("otp").asText());
        return new Courier(registered.get("agentId").asText(),
                credential.get("courierToken").asText());
    }

    private JsonNode registered(String phone) throws Exception {
        String invite = fleet.mintInvite(store, "op:test", null).token();
        MvcResult result = mvc.perform(register(invite, phone))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode verified(String phone, String otp) throws Exception {
        MvcResult result = mvc.perform(verify(phone, otp))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private void uploadDocuments(Courier courier, String... kinds) throws Exception {
        StringBuilder docs = new StringBuilder();
        for (String kind : kinds) {
            if (docs.length() > 0) docs.append(',');
            docs.append("{\"kind\":\"").append(kind).append("\",\"contentBase64\":\"c2Nhbg==\"}");
        }
        mvc.perform(bearer(post("/v1/sales/delivery/agents/" + courier.agentId() + "/documents"),
                        courier.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documents\":[" + docs + "]}"))
                .andExpect(status().isOk());
    }

    private MockHttpServletRequestBuilder register(String invite, String phone) {
        return post("/v1/sales/delivery/agents/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"inviteToken":"%s","legalName":"Juan Perez",
                         "legalAddress":{"line1":"Calle 3","city":"Santo Domingo","country":"DO"},
                         "phone":"%s",
                         "vehicle":{"kind":"MOTORCYCLE","make":"Honda","model":"CG125","plate":"K123456"},
                         "idDoc":{"type":"CEDULA","number":"001-0000000-1"}}
                        """.formatted(invite, phone));
    }

    private MockHttpServletRequestBuilder verify(String phone, String otp) {
        return post("/v1/sales/delivery/agents/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\",\"otp\":\"" + otp + "\"}");
    }

    private static MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder req, String token) {
        return req.header("Authorization", "Bearer " + token);
    }

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder req) {
        req.with(jwt().jwt(j -> j.subject("op-1").claim("roles", List.of("owner"))
                .claim("org", "tenant-fleet")));
        return req.header("X-Yaya-Store-Id", store.value());
    }

    private JsonNode getJson(MockHttpServletRequestBuilder req) throws Exception {
        MvcResult result = mvc.perform(req).andExpect(status().is2xxSuccessful()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }
}
