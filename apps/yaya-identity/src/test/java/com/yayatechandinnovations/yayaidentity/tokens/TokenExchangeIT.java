package com.yayatechandinnovations.yayaidentity.tokens;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.SignedJWT;
import com.yayatechandinnovations.yayaidentity.credentials.CredentialService;
import com.yayatechandinnovations.yayaidentity.organizations.OrganizationService;
import com.yayatechandinnovations.yayaidentity.users.User;
import com.yayatechandinnovations.yayaidentity.users.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RFC 8693 token-exchange (#6 / AC-31): the agent obtains a shopper's token via
 * login, then EXPLICITLY exchanges it for a token scoped to {@code aud:
 * yaya-sales} — the shopper is forwarded by an audience-rebinding exchange,
 * never a silent pass-through. The exchanged token preserves {@code sub}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TokenExchangeIT {

    private static final String REDIRECT_URI = "http://127.0.0.1:9999/callback";
    private static final String AGENT_ID = "yaya-agentic";
    private static final String AGENT_SECRET = "exchange-secret";
    private static final Pattern CSRF = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    private static final Pattern CODE = Pattern.compile("[?&]code=([^&]+)");

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
        r.add("yaya.identity.bootstrap.service-client.secret", () -> AGENT_SECRET);
    }

    @LocalServerPort int port;
    @Autowired UserService users;
    @Autowired OrganizationService orgs;
    @Autowired CredentialService credentials;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .cookieHandler(new CookieManager())
            .build();

    @Test
    void agentExchangesShopperTokenForSalesAudience() throws Exception {
        // A shopper who can log in.
        var org = orgs.create("Shopper Org");
        User shopper = users.createPerson("shopper@acme.test", null, "Shopper");
        credentials.setPassword(shopper.id(), "correct-horse-battery");
        users.setStatus(shopper.id(), User.Status.ACTIVE);
        orgs.addMember(org.id(), shopper.id());

        String shopperToken = loginAndGetAccessToken("shopper@acme.test", "correct-horse-battery");
        assertThat(SignedJWT.parse(shopperToken).getJWTClaimsSet().getSubject())
                .isEqualTo(shopper.id().value());

        // The agent (confidential service client) exchanges the shopper token.
        String form = form(Map.of(
                "grant_type", "urn:ietf:params:oauth:grant-type:token-exchange",
                "subject_token", shopperToken,
                "subject_token_type", "urn:ietf:params:oauth:token-type:access_token",
                "requested_token_type", "urn:ietf:params:oauth:token-type:access_token",
                "audience", "yaya-sales",
                // Request a scope the agent client owns; otherwise SAS defaults to the
                // subject token's scopes (openid/profile) which the agent isn't registered for.
                "scope", "identity.pdp"));
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder(uri("/oauth2/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("Authorization", basic(AGENT_ID, AGENT_SECRET))
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(200);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = json.readValue(resp.body(), Map.class);
        SignedJWT exchanged = SignedJWT.parse((String) body.get("access_token"));

        // Audience rebound to yaya-sales; subject preserved — the shopper, forwarded explicitly.
        assertThat(exchanged.getJWTClaimsSet().getAudience()).contains("yaya-sales");
        assertThat(exchanged.getJWTClaimsSet().getSubject()).isEqualTo(shopper.id().value());
    }

    /** Drives auth-code + PKCE login and returns the shopper's access token. */
    private String loginAndGetAccessToken(String email, String password) throws Exception {
        HttpResponse<String> loginPage = http.send(
                HttpRequest.newBuilder(uri("/login")).GET().build(), HttpResponse.BodyHandlers.ofString());
        Matcher csrf = CSRF.matcher(loginPage.body());
        assertThat(csrf.find()).isTrue();

        http.send(HttpRequest.newBuilder(uri("/login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form(Map.of(
                                "username", email, "password", password, "_csrf", csrf.group(1))))).build(),
                HttpResponse.BodyHandlers.ofString());

        String verifier = base64Url(random());
        String challenge = base64Url(sha256(verifier));
        String authorize = "/oauth2/authorize?response_type=code&client_id=yaya-console"
                + "&scope=" + enc("openid profile") + "&redirect_uri=" + enc(REDIRECT_URI)
                + "&code_challenge=" + challenge + "&code_challenge_method=S256";
        HttpResponse<String> authorized = http.send(
                HttpRequest.newBuilder(uri(authorize)).GET().build(), HttpResponse.BodyHandlers.ofString());
        Matcher code = CODE.matcher(authorized.headers().firstValue("location").orElseThrow());
        assertThat(code.find()).isTrue();

        HttpResponse<String> token = http.send(HttpRequest.newBuilder(uri("/oauth2/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form(Map.of(
                                "grant_type", "authorization_code",
                                "code", java.net.URLDecoder.decode(code.group(1), StandardCharsets.UTF_8),
                                "redirect_uri", REDIRECT_URI, "client_id", "yaya-console",
                                "code_verifier", verifier)))).build(),
                HttpResponse.BodyHandlers.ofString());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = json.readValue(token.body(), Map.class);
        return (String) body.get("access_token");
    }

    private URI uri(String path) { return URI.create("http://localhost:" + port + path); }

    private static String basic(String u, String p) {
        return "Basic " + Base64.getEncoder().encodeToString((u + ":" + p).getBytes(StandardCharsets.UTF_8));
    }

    private static String form(Map<String, String> params) {
        return params.entrySet().stream().map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .reduce((a, b) -> a + "&" + b).orElse("");
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    private static byte[] random() { byte[] b = new byte[32]; new java.security.SecureRandom().nextBytes(b); return b; }
    private static byte[] sha256(String s) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.US_ASCII));
    }
    private static String base64Url(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
}
