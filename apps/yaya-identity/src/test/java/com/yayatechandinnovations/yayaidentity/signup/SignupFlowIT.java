package com.yayatechandinnovations.yayaidentity.signup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaidentity.credentials.CredentialService;
import com.yayatechandinnovations.yayaidentity.organizations.OrganizationService;
import com.yayatechandinnovations.yayaidentity.platform.Ids.OrgId;
import com.yayatechandinnovations.yayaidentity.platform.Ids.UserId;
import com.yayatechandinnovations.yayaidentity.roles.RoleAssignmentService;
import com.yayatechandinnovations.yayaidentity.users.User;
import com.yayatechandinnovations.yayaidentity.users.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phone-verified self-registration end to end (local OTP channel + dev echo):
 * register → verify yields an ACTIVE user who owns a brand-new org — the same
 * shape OwnerBootstrap produces. Wrong codes, reuse, expiry, duplicates, weak
 * passwords, and resend cooldowns are all refused with distinct statuses.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SignupFlowIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("yaya_identity");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("yaya.identity.secrets.encryption-key",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
        r.add("yaya.identity.signup.enabled", () -> "true");
        r.add("yaya.identity.signup.otp-echo-enabled", () -> "true");
        r.add("yaya.identity.signup.resend-cooldown-seconds", () -> "3600");
        r.add("yaya.identity.signup.max-sends-per-phone-per-hour", () -> "10");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserService users;
    @Autowired OrganizationService orgs;
    @Autowired CredentialService credentials;
    @Autowired RoleAssignmentService assignments;
    @Autowired PendingSignupRepository pendings;

    @Test
    void registerThenVerifyCreatesActiveOwnerOfANewOrg() throws Exception {
        JsonNode registered = postJson("/signup/register", """
                {"orgName":"Colmado Dona Ana","displayName":"Ana Perez",
                 "email":"ana@acme.test","phone":"+1 (809) 555-1234",
                 "password":"correct-horse-battery"}""", 200);
        String signupId = registered.get("signupId").asText();
        String otp = registered.get("devOtp").asText();
        assertThat(registered.get("otpSent").asBoolean()).isTrue();

        // Until the phone is proven, the user cannot authenticate and no org exists.
        User pending = users.byEmail("ana@acme.test").orElseThrow();
        assertThat(pending.status()).isEqualTo(User.Status.INVITED);

        JsonNode verified = postJson("/signup/verify",
                "{\"signupId\":\"" + signupId + "\",\"code\":\"" + otp + "\"}", 200);
        String orgId = verified.get("orgId").asText();

        User active = users.byId(new UserId(verified.get("userId").asText())).orElseThrow();
        assertThat(active.status()).isEqualTo(User.Status.ACTIVE);
        assertThat(active.phone()).isEqualTo("+18095551234");
        assertThat(credentials.verifyPassword(active.id(), "correct-horse-battery")).isTrue();
        assertThat(orgs.membersOf(new OrgId(orgId))).hasSize(1);
        assertThat(assignments.assignmentsOf(active.id(), new OrgId(orgId))).isNotEmpty();
    }

    @Test
    void wrongCodeIsRefusedAndTheSignupSurvives() throws Exception {
        JsonNode registered = register("+18095550001", "wrong@acme.test");
        String signupId = registered.get("signupId").asText();

        postJson("/signup/verify",
                "{\"signupId\":\"" + signupId + "\",\"code\":\"000000\"}", 400);

        postJson("/signup/verify", "{\"signupId\":\"" + signupId
                + "\",\"code\":\"" + registered.get("devOtp").asText() + "\"}", 200);
    }

    @Test
    void aCompletedSignupCannotBeVerifiedTwice() throws Exception {
        JsonNode registered = register("+18095550002", "twice@acme.test");
        String signupId = registered.get("signupId").asText();
        String otp = registered.get("devOtp").asText();
        postJson("/signup/verify", "{\"signupId\":\"" + signupId + "\",\"code\":\"" + otp + "\"}", 200);
        postJson("/signup/verify", "{\"signupId\":\"" + signupId + "\",\"code\":\"" + otp + "\"}", 400);
    }

    @Test
    void expiredSignupAnswersGone() throws Exception {
        JsonNode registered = register("+18095550003", "late@acme.test");
        String signupId = registered.get("signupId").asText();
        PendingSignupEntity pending = pendings.findById(signupId).orElseThrow();
        pending.setExpiresAt(Instant.now().minusSeconds(1));
        pendings.save(pending);

        postJson("/signup/verify", "{\"signupId\":\"" + signupId
                + "\",\"code\":\"" + registered.get("devOtp").asText() + "\"}", 410);
    }

    @Test
    void activeEmailAndActivePhoneConflict() throws Exception {
        JsonNode first = register("+18095550004", "taken@acme.test");
        postJson("/signup/verify", "{\"signupId\":\"" + first.get("signupId").asText()
                + "\",\"code\":\"" + first.get("devOtp").asText() + "\"}", 200);

        postJson("/signup/register", """
                {"orgName":"Second","displayName":"Copy","email":"taken@acme.test",
                 "phone":"+18095550104","password":"correct-horse-battery"}""", 409);
        postJson("/signup/register", """
                {"orgName":"Second","displayName":"Copy","email":"other@acme.test",
                 "phone":"+18095550004","password":"correct-horse-battery"}""", 409);
    }

    @Test
    void reRegisteringAPendingSignupSupersedesIt() throws Exception {
        JsonNode first = register("+18095550005", "retry@acme.test");
        JsonNode second = postJson("/signup/register", """
                {"orgName":"Fixed Name","displayName":"Retry",
                 "email":"retry@acme.test","phone":"+18095550105",
                 "password":"correct-horse-battery-2"}""", 200);

        // The first pending signup is dead; only the second can complete.
        postJson("/signup/verify", "{\"signupId\":\"" + first.get("signupId").asText()
                + "\",\"code\":\"" + first.get("devOtp").asText() + "\"}", 400);
        JsonNode verified = postJson("/signup/verify",
                "{\"signupId\":\"" + second.get("signupId").asText()
                        + "\",\"code\":\"" + second.get("devOtp").asText() + "\"}", 200);
        assertThat(verified.get("orgName").asText()).isEqualTo("Fixed Name");
    }

    @Test
    void weakPasswordAndMalformedInputAreRefused() throws Exception {
        postJson("/signup/register", """
                {"orgName":"Weak","displayName":"W","email":"weak@acme.test",
                 "phone":"+18095550006","password":"short"}""", 400);
        postJson("/signup/register", """
                {"orgName":"NoPhone","displayName":"N","email":"nophone@acme.test",
                 "phone":"809-555","password":"correct-horse-battery"}""", 400);
        postJson("/signup/register", """
                {"orgName":"NoEmail","displayName":"N","email":"not-an-email",
                 "phone":"+18095550007","password":"correct-horse-battery"}""", 400);
    }

    @Test
    void resendHonoursTheCooldown() throws Exception {
        JsonNode registered = register("+18095550008", "resend@acme.test");
        // Cooldown is 1h in this context; the register itself counted as a send.
        postJson("/signup/resend",
                "{\"signupId\":\"" + registered.get("signupId").asText() + "\"}", 429);
    }

    private JsonNode register(String phone, String email) throws Exception {
        return postJson("/signup/register", """
                {"orgName":"Org of %s","displayName":"Someone",
                 "email":"%s","phone":"%s","password":"correct-horse-battery"}"""
                .formatted(email, email, phone), 200);
    }

    private JsonNode postJson(String path, String body, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        String content = result.getResponse().getContentAsString();
        return content.isBlank() ? json.createObjectNode() : json.readTree(content);
    }
}
