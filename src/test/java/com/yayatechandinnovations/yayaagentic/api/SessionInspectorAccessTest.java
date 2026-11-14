package com.yayatechandinnovations.yayaagentic.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.yayatechandinnovations.yayaagentic.auth.jwt.TenantJwtSecretsChangedEvent;
import com.yayatechandinnovations.yayaagentic.auth.secrets.JwtSecretCipher;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.operator_auth.apikeys.ApiKeyCache;
import com.yayatechandinnovations.yayaagentic.operator_auth.apikeys.FailedBearerRateLimiter;
import com.yayatechandinnovations.yayaagentic.operator_auth.ratelimit.LoginRateLimiter;
import com.yayatechandinnovations.yayaagentic.persistence.ApiKeyEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ApiKeyRepository;
import com.yayatechandinnovations.yayaagentic.persistence.TenantJwtSecretEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantJwtSecretRepository;
import com.yayatechandinnovations.yayaagentic.profile.Profile;
import com.yayatechandinnovations.yayaagentic.profile.ProfileRegistry;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import com.yayatechandinnovations.yayaagentic.support.UserAccountsTestSupport;
import com.yayatechandinnovations.yayaagentic.support.UserAccountsTestSupport.ProvisionedClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * Who may read {@code GET /v1/sessions/{id}/inspector} (prompts, working
 * memory, tool results). A session id alone opens nothing: a user's session
 * is read by that user or a workspace admin; a playground session by an
 * operator of its workspace.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.user-accounts.enabled=true",
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key=",
        "yaya.agentic.secrets.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionInspectorAccessTest {

    private static final String ISS = "inspector-test-identity";
    private static final String SECRET = "inspector-access-test-signing-secret-32b+";
    private static final String PROFILE = "inspected";

    @Autowired WebTestClient base;
    @Autowired UserAccountsTestSupport users;
    @Autowired LoginRateLimiter loginRateLimiter;
    @Autowired FailedBearerRateLimiter bearerRateLimiter;
    @Autowired ApiKeyCache keyCache;
    @Autowired ApiKeyRepository keys;
    @Autowired TenantJwtSecretRepository jwtSecrets;
    @Autowired JwtSecretCipher cipher;
    @Autowired ApplicationEventPublisher events;
    @Autowired ProfileRegistry profiles;
    @Autowired ObjectMapper json;

    private ProvisionedClient alice;   // OWNER of the workspace the sessions live in
    private ProvisionedClient bob;     // OWNER of another workspace
    private String ws;
    private String owner;
    private String ownerToken;
    private String ownedSession;
    private String playgroundSession;
    private String actAsSession;

    @BeforeAll
    void install() {
        loginRateLimiter.resetAll();
        alice = users.registerVerifyAndLogin(base, "alice-" + UUID.randomUUID() + "@t.io", "Aa1!Pass-strong-xyz", "Alice");
        bob = users.registerVerifyAndLogin(base, "bob-" + UUID.randomUUID() + "@t.io", "Bb2!Pass-strong-xyz", "Bob");
        ws = alice.user().defaultWorkspaceCode();

        JwtSecretCipher.Encrypted enc = cipher.encrypt(SECRET);
        jwtSecrets.save(new TenantJwtSecretEntity(ws, ISS, enc.ciphertext(), enc.iv(), "[]"));
        events.publishEvent(new TenantJwtSecretsChangedEvent(ws));
        profiles.register(new Profile(new Ids.ProfileId(PROFILE, 1), new Ids.TenantId(ws), "Inspected",
                "Hola.", "You are an assistant.", List.of(), List.of(), null, "es", Map.of(),
                Map.of(), null, null));

        owner = "u-" + UUID.randomUUID();
        ownerToken = token(owner);
        ownedSession = start(Map.of("tenant", ws, "profileId", PROFILE), ownerToken);
        playgroundSession = start(Map.of("tenant", ws, "profileId", PROFILE), null);
        actAsSession = start(Map.of("tenant", ws, "profileId", PROFILE, "actAs",
                Map.of("kind", "raw-token", "scheme", "Bearer", "token", token("u-" + UUID.randomUUID()))), null);
    }

    @BeforeEach
    void reset() {
        loginRateLimiter.resetAll();
        bearerRateLimiter.resetAll();
    }

    // ---- a session a user's app started ---------------------------------

    @Test
    void a_users_session_is_not_readable_with_the_session_id_alone() {
        inspect(base, ownedSession).expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("auth_required");
    }

    @Test
    void a_users_session_is_readable_with_that_users_token() {
        inspect(bearer(ownerToken), ownedSession).expectStatus().isOk()
                .expectBody().jsonPath("$.workingMemory").exists();
        // A fresh token of the same user works; the token is checked on every read.
        inspect(bearer(token(owner)), ownedSession).expectStatus().isOk();
    }

    @Test
    void a_users_session_is_hidden_from_another_user_and_from_bad_tokens() {
        inspect(bearer(token("u-" + UUID.randomUUID())), ownedSession).expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("session_not_found");
        inspect(bearer(token(owner, -60)), ownedSession).expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("token_expired");
        inspect(bearer("not-a-token"), ownedSession).expectStatus().isUnauthorized();
    }

    @Test
    void a_workspace_admin_reads_a_users_session_and_other_operators_do_not() {
        inspect(alice.client(), ownedSession).expectStatus().isOk();
        inspect(bearer(mintKey(alice, ws, "ADMIN")), ownedSession).expectStatus().isOk();

        // Below ADMIN, or an operator of another workspace: as if it did not exist.
        inspect(bearer(mintKey(alice, ws, "MEMBER")), ownedSession).expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("session_not_found");
        inspect(bob.client(), ownedSession).expectStatus().isNotFound();
        inspect(bearer(mintKey(bob, bob.user().defaultWorkspaceCode(), "OWNER")), ownedSession)
                .expectStatus().isNotFound();
    }

    @Test
    void a_platform_scope_key_reads_a_users_session_in_any_workspace() {
        String secret = mintKey(bob, bob.user().defaultWorkspaceCode(), "MEMBER");
        inspect(bearer(secret), ownedSession).expectStatus().isNotFound();

        ApiKeyEntity key = keys.findAll().stream()
                .filter(k -> secret.startsWith(k.getKeyPrefix())).findFirst().orElseThrow();
        key.setPlatformScope(true);
        keys.save(key);
        keyCache.clear();

        inspect(bearer(secret), ownedSession).expectStatus().isOk();
    }

    @Test
    void an_unknown_or_revoked_key_is_refused_not_ignored() {
        inspect(bearer("yk_live_" + "x".repeat(32)), ownedSession).expectStatus().isUnauthorized();
    }

    // ---- a playground session -------------------------------------------

    @Test
    void a_playground_session_is_not_readable_with_the_session_id_alone() {
        inspect(base, playgroundSession).expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("auth_required");
        inspect(base, actAsSession).expectStatus().isUnauthorized();
        // An end-user token is not an operator.
        inspect(bearer(ownerToken), playgroundSession).expectStatus().isUnauthorized();
    }

    @Test
    void the_console_operator_reads_the_playground_sessions_of_their_workspace() {
        inspect(alice.client(), playgroundSession).expectStatus().isOk()
                .expectBody().jsonPath("$.workingMemory").exists();
        inspect(alice.client(), actAsSession).expectStatus().isOk();
        inspect(bearer(mintKey(alice, ws, "MEMBER")), playgroundSession).expectStatus().isOk();
        inspect(bearer(mintKey(alice, ws, "MEMBER")), actAsSession).expectStatus().isOk();

        inspect(bob.client(), playgroundSession).expectStatus().isNotFound();
        inspect(bob.client(), actAsSession).expectStatus().isNotFound();
    }

    @Test
    void a_session_that_does_not_exist_is_a_404_for_everyone() {
        String unknown = UUID.randomUUID().toString();
        inspect(base, unknown).expectStatus().isNotFound();
        inspect(alice.client(), unknown).expectStatus().isNotFound();
        inspect(base, "not-a-uuid").expectStatus().isNotFound();
    }

    // ------------------------------------------------------------------

    private static WebTestClient.ResponseSpec inspect(WebTestClient as, String sessionId) {
        return as.get().uri("/v1/sessions/{id}/inspector", sessionId).exchange();
    }

    private WebTestClient bearer(String secret) {
        return base.mutate().defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + secret).build();
    }

    private String start(Map<String, Object> body, String token) {
        var req = base.post().uri("/v1/sessions").contentType(MediaType.APPLICATION_JSON);
        if (token != null) req = req.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        byte[] out = req.bodyValue(body).exchange().expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();
        try {
            return json.readTree(out).get("sessionId").asText();
        } catch (Exception e) {
            throw new AssertionError("bad start response", e);
        }
    }

    private String mintKey(ProvisionedClient as, String workspace, String role) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "k-" + UUID.randomUUID().toString().substring(0, 8));
        body.put("role", role);
        byte[] out = as.client().post().uri("/v1/admin/workspaces/{w}/api-keys", workspace)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isCreated().expectBody().returnResult().getResponseBody();
        try {
            JsonNode created = json.readTree(out);
            return created.get("secret").asText();
        } catch (Exception e) {
            throw new AssertionError("bad create response", e);
        }
    }

    private static String token(String subject) {
        return token(subject, 300);
    }

    private static String token(String subject, int ttlSeconds) {
        try {
            Date now = new Date();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(ISS).subject(subject)
                    .issueTime(now).expirationTime(new Date(now.getTime() + ttlSeconds * 1000L))
                    .jwtID(UUID.randomUUID().toString()).build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
