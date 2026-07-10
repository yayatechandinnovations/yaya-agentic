package com.yayatechandinnovations.yayaidentity.tokens;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.SignedJWT;
import com.yayatechandinnovations.yayaidentity.credentials.CredentialService;
import com.yayatechandinnovations.yayaidentity.organizations.Organization;
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
 * The full human auth-code + PKCE flow, end to end over HTTP: form login (CSRF +
 * session cookie) → authorization endpoint → code → token exchange. Proves I1's
 * human-login exit criterion and that the issued token carries {@code sub} (the
 * user id) and the {@code org} claim. Complements TokenIssuanceIT (machine login).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AuthCodeLoginE2EIT {

    private static final String REDIRECT_URI = "http://127.0.0.1:9999/callback";
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
    }

    @LocalServerPort int port;
    @Autowired UserService users;
    @Autowired OrganizationService orgs;
    @Autowired CredentialService credentials;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void humanLogsInAndGetsATokenWithSubAndOrg() throws Exception {
        // Seed: an active user with a password, member of an org.
        Organization org = orgs.create("Console Org");
        User user = users.createPerson("operator@acme.test", null, "Operator");
        credentials.setPassword(user.id(), "correct-horse-battery");
        users.setStatus(user.id(), User.Status.ACTIVE);
        orgs.addMember(org.id(), user.id());

        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .cookieHandler(new CookieManager())
                .build();

        // 1. A COLD browser starts at /oauth2/authorize (exactly what the console
        //    does) and is sent to the login form.
        String verifier = base64Url(randomBytes());
        String challenge = base64Url(sha256(verifier));
        String authorize = "/oauth2/authorize?response_type=code"
                + "&client_id=yaya-console"
                + "&scope=" + enc("openid profile")
                + "&redirect_uri=" + enc(REDIRECT_URI)
                + "&code_challenge=" + challenge
                + "&code_challenge_method=S256";
        HttpResponse<String> sentToLogin = http.send(
                HttpRequest.newBuilder(uri(authorize))
                        .header("Accept", "text/html").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(sentToLogin.statusCode()).isEqualTo(302);
        assertThat(sentToLogin.headers().firstValue("location").orElse("")).endsWith("/login");

        // 2. GET the login page → CSRF token (+ session cookie captured by the CookieManager).
        HttpResponse<String> loginPage = http.send(
                HttpRequest.newBuilder(uri("/login")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Matcher csrfMatch = CSRF.matcher(loginPage.body());
        assertThat(csrfMatch.find()).as("login page carries a CSRF token").isTrue();
        String csrf = csrfMatch.group(1);

        // 3. POST credentials → the SAVED authorize request resumes; a fixed
        //    "redirect to /" here once stranded the whole OAuth flow.
        HttpResponse<String> loggedIn = http.send(
                postForm("/login", Map.of(
                        "username", "operator@acme.test",
                        "password", "correct-horse-battery",
                        "_csrf", csrf)),
                HttpResponse.BodyHandlers.ofString());
        assertThat(loggedIn.statusCode()).isEqualTo(302);
        String resumed = loggedIn.headers().firstValue("location").orElse("");
        assertThat(resumed).contains("/oauth2/authorize");

        // 4. Following the resumed authorize → 302 to redirect_uri?code=…
        HttpResponse<String> authorized = http.send(
                HttpRequest.newBuilder(URI.create(resumed)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(authorized.statusCode()).isEqualTo(302);
        String location = authorized.headers().firstValue("location").orElseThrow();
        Matcher codeMatch = CODE.matcher(location);
        assertThat(codeMatch.find()).as("authorization redirect carries a code: " + location).isTrue();
        String code = URLDecode(codeMatch.group(1));

        // 5. Token exchange (public client → client_id in body, code_verifier for PKCE).
        HttpResponse<String> tokenResp = http.send(
                postForm("/oauth2/token", Map.of(
                        "grant_type", "authorization_code",
                        "code", code,
                        "redirect_uri", REDIRECT_URI,
                        "client_id", "yaya-console",
                        "code_verifier", verifier)),
                HttpResponse.BodyHandlers.ofString());
        assertThat(tokenResp.statusCode()).isEqualTo(200);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = json.readValue(tokenResp.body(), Map.class);
        assertThat(body).containsKey("id_token");                    // openid scope
        String accessToken = (String) body.get("access_token");
        assertThat(accessToken).isNotBlank();

        // 6. The token identifies the user (sub) and their org.
        SignedJWT jwt = SignedJWT.parse(accessToken);
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo(user.id().value());
        assertThat(jwt.getJWTClaimsSet().getStringClaim("org")).isEqualTo(org.id().value());
    }

    /**
     * The cold-start path a real browser takes: no session, straight to
     * /oauth2/authorize. It must be SENT TO THE LOGIN FORM — the SAS chain's
     * resource-server 401 entry point once won here and dead-ended humans.
     */
    @Test
    void unauthenticatedBrowserAtAuthorizeIsRedirectedToLogin() throws Exception {
        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .cookieHandler(new CookieManager())
                .build();

        String authorize = "/oauth2/authorize?response_type=code"
                + "&client_id=yaya-console"
                + "&scope=" + enc("openid profile")
                + "&redirect_uri=" + enc(REDIRECT_URI)
                + "&code_challenge=" + base64Url(sha256(base64Url(randomBytes())))
                + "&code_challenge_method=S256";
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(uri(authorize))
                        .header("Accept", "text/html,application/xhtml+xml")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("location").orElse(""))
                .endsWith("/login");
    }

    // --- helpers ---

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpRequest postForm(String path, Map<String, String> params) {
        String form = params.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .reduce((a, b) -> a + "&" + b).orElse("");
        return HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String URLDecode(String s) {
        return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static byte[] randomBytes() {
        byte[] b = new byte[32];
        new java.security.SecureRandom().nextBytes(b);
        return b;
    }

    private static byte[] sha256(String s) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.US_ASCII));
    }

    private static String base64Url(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
