package com.yayatechandinnovations.yayaidentity.signup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaidentity.credentials.CredentialService;
import com.yayatechandinnovations.yayaidentity.organizations.InvitationService;
import com.yayatechandinnovations.yayaidentity.organizations.Organization;
import com.yayatechandinnovations.yayaidentity.organizations.OrganizationService;
import com.yayatechandinnovations.yayaidentity.platform.Ids.UserId;
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

import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The public onboarding surface with signup at its DEFAULTS (disabled):
 * invitation acceptance still works unauthenticated — the admin-issued token is
 * the authorization — while open registration answers 403. This is the "second
 * person joins the org" path.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class InvitationAcceptHttpIT {

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
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired OrganizationService orgs;
    @Autowired InvitationService invitations;
    @Autowired UserService users;
    @Autowired CredentialService credentials;

    @Test
    void inviteeAcceptsOverHttpWithoutAuthentication() throws Exception {
        Organization org = orgs.create("Http Accept Org");
        var invite = invitations.invite(org.id(), "joiner@acme.test", "admin", null,
                Duration.ofDays(7));

        MvcResult result = mvc.perform(post("/signup/invitations/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + invite.token()
                                + "\",\"displayName\":\"Joiner\","
                                + "\"password\":\"correct-horse-battery\"}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("orgId").asText()).isEqualTo(org.id().value());
        User user = users.byId(new UserId(body.get("userId").asText())).orElseThrow();
        assertThat(user.status()).isEqualTo(User.Status.ACTIVE);
        assertThat(credentials.verifyPassword(user.id(), "correct-horse-battery")).isTrue();
    }

    @Test
    void usedOrUnknownTokensAnswerBadRequest() throws Exception {
        Organization org = orgs.create("Used Token Org");
        var invite = invitations.invite(org.id(), "once@acme.test", null, null, Duration.ofDays(7));
        invitations.accept(invite.token(), "Once", "correct-horse-battery");

        mvc.perform(post("/signup/invitations/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + invite.token()
                                + "\",\"displayName\":\"Again\",\"password\":\"correct-horse-battery\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/signup/invitations/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"never-was\",\"displayName\":\"X\","
                                + "\"password\":\"correct-horse-battery\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void openRegistrationIsRefusedWhileDisabled() throws Exception {
        mvc.perform(post("/signup/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orgName\":\"Nope\",\"displayName\":\"N\","
                                + "\"email\":\"nope@acme.test\",\"phone\":\"+18095550100\","
                                + "\"password\":\"correct-horse-battery\"}"))
                .andExpect(status().isForbidden());
    }
}
