package com.yayatechandinnovations.yayaagentic.support;

import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.OperatorAccountService;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.SignupRequest;
import com.yayatechandinnovations.yayaagentic.persistence.EmailOutboxEntity;
import com.yayatechandinnovations.yayaagentic.persistence.EmailOutboxRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test-only helper: registers + verifies + signs in users without making
 * each test re-implement the dance.
 *
 * <p>Tokens are extracted by reading {@code email_outbox} rows in the
 * dev-console profile — that's where the raw token lives once. Production
 * test profiles must therefore boot with {@code yaya.agentic.email.provider=dev-console}
 * (or unset, since dev-console is the {@code matchIfMissing=true} default).</p>
 */
@Component
public class UserAccountsTestSupport {

    /** "Verification token: <token>\n" — emitted by {@code DefaultOperatorAccountService}. */
    private static final Pattern VERIFY_TOKEN_RE = Pattern.compile("Verification token: (\\S+)");
    private static final Pattern RESET_TOKEN_RE = Pattern.compile("Reset token: (\\S+)");
    private static final Pattern INVITE_TOKEN_RE = Pattern.compile("Invitation token: (\\S+)");

    @Autowired OperatorAccountService accounts;
    @Autowired EmailOutboxRepository outbox;

    /**
     * Register + verify a user. Returns the {@code (userId, defaultWorkspaceCode)}
     * pair the verification flow produced.
     */
    public ProvisionedUser registerAndVerify(String email, String password, String displayName) {
        OperatorAccountService.SignupOutcome signup = accounts.register(
                new SignupRequest(email, displayName, password.toCharArray()),
                OperatorAccountService.RequestMeta.empty());
        assertThat(signup.created()).as("test users must be new emails").isTrue();

        String token = grabRawVerificationToken(email);
        OperatorAccountService.VerifyOutcome verify = accounts.verifyEmail(token,
                OperatorAccountService.RequestMeta.empty());
        return new ProvisionedUser(
                verify.userId().value().toString(),
                verify.defaultWorkspaceId().value(),
                email,
                password);
    }

    /**
     * Sign in via the real {@code /v1/auth/login} flow. Returns a
     * {@link WebTestClient} pre-primed with the {@code YAYA_SESSION} +
     * {@code XSRF-TOKEN} cookies and the matching {@code X-XSRF-TOKEN}
     * header — drop-in for subsequent admin calls.
     */
    public WebTestClient loginAs(WebTestClient client, String email, String password) {
        // First call mints the XSRF cookie.
        var meHeaders = client.get().uri("/v1/auth/me").exchange().returnResult(byte[].class).getResponseHeaders();
        String xsrf = readCookie(meHeaders, "XSRF-TOKEN");
        assertThat(xsrf).as("CsrfWebFilter should mint XSRF-TOKEN on first GET").isNotNull();

        var loginHeaders = client.post().uri("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .cookie("XSRF-TOKEN", xsrf)
                .header("X-XSRF-TOKEN", xsrf)
                .bodyValue(Map.of("username", email, "password", password))
                .exchange()
                .expectStatus().isOk()
                .returnResult(byte[].class)
                .getResponseHeaders();
        String session = readCookie(loginHeaders, "YAYA_SESSION");
        assertThat(session).as("login should set YAYA_SESSION").isNotNull();

        return client.mutate()
                .defaultCookie("XSRF-TOKEN", xsrf)
                .defaultCookie("YAYA_SESSION", session)
                .defaultHeader("X-XSRF-TOKEN", xsrf)
                .build();
    }

    /** Combo: register + verify + login. Returns the primed client and the user identity. */
    public ProvisionedClient registerVerifyAndLogin(WebTestClient client,
                                                    String email, String password, String displayName) {
        ProvisionedUser u = registerAndVerify(email, password, displayName);
        return new ProvisionedClient(loginAs(client, email, password), u);
    }

    /** Read the most recent verification mail for {@code email} and pull the raw token out of the body. */
    public String grabRawVerificationToken(String email) {
        return grabToken(email, "verify-email", VERIFY_TOKEN_RE)
                .orElseThrow(() -> new AssertionError("no verification token in outbox for " + email));
    }

    public String grabRawResetToken(String email) {
        return grabToken(email, "reset-password", RESET_TOKEN_RE)
                .orElseThrow(() -> new AssertionError("no reset token in outbox for " + email));
    }

    public String grabRawInvitationToken(String email) {
        return grabToken(email, "invitation", INVITE_TOKEN_RE)
                .orElseThrow(() -> new AssertionError("no invitation token in outbox for " + email));
    }

    private Optional<String> grabToken(String email, String templateId, Pattern re) {
        return outbox.findAll().stream()
                .filter(o -> templateId.equals(o.getTemplateId()))
                .filter(o -> email.equalsIgnoreCase(o.getToAddress()))
                .max(Comparator.comparing(EmailOutboxEntity::getCreatedAt))
                .map(EmailOutboxEntity::getBodyText)
                .flatMap(body -> {
                    Matcher m = re.matcher(body);
                    return m.find() ? Optional.of(m.group(1)) : Optional.empty();
                });
    }

    private static String readCookie(org.springframework.http.HttpHeaders headers, String name) {
        var values = headers.get(org.springframework.http.HttpHeaders.SET_COOKIE);
        if (values == null) return null;
        String needle = name + "=";
        for (String v : values) {
            if (v.startsWith(needle)) {
                int end = v.indexOf(';');
                return end < 0 ? v.substring(needle.length())
                               : v.substring(needle.length(), end);
            }
        }
        return null;
    }

    public record ProvisionedUser(String userId, String defaultWorkspaceCode, String email, String password) {}

    public record ProvisionedClient(WebTestClient client, ProvisionedUser user) {}
}
