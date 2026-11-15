package com.yayatechandinnovations.yayaagentic.headless;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.yayatechandinnovations.yayaagentic.auth.Authorizer;
import com.yayatechandinnovations.yayaagentic.auth.AuthzContext;
import com.yayatechandinnovations.yayaagentic.auth.AuthzDecision;
import com.yayatechandinnovations.yayaagentic.auth.jwt.TenantJwtSecretsChangedEvent;
import com.yayatechandinnovations.yayaagentic.auth.secrets.JwtSecretCipher;
import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.engine.bootstrap.M0Catalog;
import com.yayatechandinnovations.yayaagentic.llm.LlmClient;
import com.yayatechandinnovations.yayaagentic.memory.WorkingMemory;
import com.yayatechandinnovations.yayaagentic.persistence.TenantEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantJwtSecretEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantJwtSecretRepository;
import com.yayatechandinnovations.yayaagentic.persistence.TenantRepository;
import com.yayatechandinnovations.yayaagentic.profile.Capability;
import com.yayatechandinnovations.yayaagentic.profile.Profile;
import com.yayatechandinnovations.yayaagentic.profile.ProfileRegistry;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import com.yayatechandinnovations.yayaagentic.tenant.TenantOrigins;
import com.yayatechandinnovations.yayaagentic.testsupport.ScriptedLlmClient;
import com.yayatechandinnovations.yayaagentic.tool.HttpToolSpec;
import com.yayatechandinnovations.yayaagentic.tool.ToolDescriptor;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandler;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandlerRef;
import com.yayatechandinnovations.yayaagentic.tool.ToolPolicy;
import com.yayatechandinnovations.yayaagentic.usage.UsageMeter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * The harness of the headless-run tests ({@code docs/guides/admin-panel-sessions.md}
 * §17): one tenant per test class whose backend is a WireMock server, an
 * owner profile with read and write tools as the install's catalogue has
 * them, a scripted model, and the two tokens of a run — the service token of
 * the request and the read-only run token of the person it answers for.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import({TestcontainersConfiguration.class, HeadlessRunTestBase.Config.class})
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key=",
        "spring.main.allow-bean-definition-overriding=true",
        "yaya.agentic.secrets.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "yaya.agentic.sessions.messages-per-minute=400",
        "yaya.agentic.sessions.session-starts-per-minute=400",
        // The tenant's backend is WireMock on localhost.
        "yaya.agentic.http-tools.allow-private-networks=true"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class HeadlessRunTestBase {

    static final String STAFF = "TestIdentity/staff";
    static final String STAFF_SECRET = "the-install-signing-key-for-the-headless-run-test";
    static final String SERVICE_SECRET = "the-sender-credential-the-install-and-yaya-share-32+";
    static final String PROFILE = "hl_owner";
    static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    @Autowired WebTestClient client;
    @Autowired ScriptedLlmClient llm;
    @Autowired ProbeTool probe;
    @Autowired TenantRepository tenants;
    @Autowired TenantJwtSecretRepository jwtSecrets;
    @Autowired JwtSecretCipher cipher;
    @Autowired ApplicationEventPublisher events;
    @Autowired ProfileRegistry profiles;
    @Autowired M0Catalog catalog;
    @Autowired TenantOrigins tenantOrigins;
    @Autowired RecordingWorkingMemory memory;
    @Autowired ObjectMapper json;
    @Autowired UsageMeter usage;
    @Autowired JdbcTemplate jdbc;
    @Autowired HeadlessRunStore store;
    @Autowired HeadlessRunService service;

    final String run = UUID.randomUUID().toString().substring(0, 8);
    final String tenant = "hl-" + run;
    final String business = "org-" + run;
    final String staffIss = "hl-identity-" + run;
    final String serviceIss = "hl-sender-" + run;
    WireMockServer backend;

    @BeforeAll
    void install() {
        backend = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        backend.start();
        installTenant(tenant, business, staffIss, serviceIss);
        registerCatalogue();
        registerProfile(tenant, 8, null);
    }

    @AfterAll
    void stopBackend() {
        if (backend != null) backend.stop();
    }

    @BeforeEach
    void reset() {
        llm.reset();
        probe.reset();
        memory.reset();
        backend.resetAll();
        backend.stubFor(any(urlPathEqualTo("/api/ai/me")).willReturn(ok("{\"ok\":true,\"name\":\"Dueña\"}")));
        backend.stubFor(any(urlPathEqualTo("/api/ai/sales")).willReturn(ok("{\"ok\":true,\"total\":\"1250.00\"}")));
        backend.stubFor(any(urlPathEqualTo("/api/ai/briefing")).willReturn(ok("{\"ok\":true,\"alerts\":0}")));
        backend.stubFor(any(urlPathEqualTo("/api/ai/customers")).willReturn(ok("{\"ok\":true}")));
        backend.stubFor(any(urlPathEqualTo("/api/ai/memory")).willReturn(ok("{\"ok\":true}")));
        backend.stubFor(any(urlPathEqualTo("/api/ai/export")).willReturn(ok("{\"ok\":true}")));
        backend.stubFor(any(urlPathEqualTo("/api/ai/toggle")).willReturn(ok("{\"ok\":true}")));
        backend.stubFor(any(urlPathEqualTo("/api/ai/expired"))
                .willReturn(aResponse().withStatus(401).withBody("{\"error\":\"unauthorized\"}")));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder ok(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
    }

    /** A tenant that trusts one issuer for its staff (pinned to {@code org}) and one for its service. */
    void installTenant(String id, String orgId, String staffIssuer, String serviceIssuer) {
        TenantEntity t = new TenantEntity(id, "Headless " + id);
        t.setHostBaseUrl(backend.baseUrl());
        tenants.save(t);
        JwtSecretCipher.Encrypted staff = cipher.encrypt(STAFF_SECRET);
        TenantJwtSecretEntity staffTrust = new TenantJwtSecretEntity(id, staffIssuer, staff.ciphertext(), staff.iv(), "[]");
        staffTrust.setPrincipalKind(STAFF);
        staffTrust.setRequiredClaimsJson("{\"org\":\"" + orgId + "\"}");
        jwtSecrets.save(staffTrust);
        JwtSecretCipher.Encrypted sender = cipher.encrypt(SERVICE_SECRET);
        jwtSecrets.save(new TenantJwtSecretEntity(id, serviceIssuer, sender.ciphertext(), sender.iv(), "[]"));
        events.publishEvent(new TenantJwtSecretsChangedEvent(id));
        tenantOrigins.evict();
    }

    /**
     * The catalogue: reads flagged for headless use, reads that are not, the
     * two POST tools the platform's own "read-only" test lets through
     * ({@code idempotent && !confirmable}), a confirmable write, and bean
     * tools whose result asks for a person.
     */
    private void registerCatalogue() {
        http("hl_me", HttpToolSpec.HttpMethod.GET, "/api/ai/me", headless());
        http("hl_sales", HttpToolSpec.HttpMethod.GET, "/api/ai/sales?what={what}", headless());
        http("hl_briefing", HttpToolSpec.HttpMethod.GET, "/api/ai/briefing", headless());
        http("hl_expired", HttpToolSpec.HttpMethod.GET, "/api/ai/expired", headless());
        http("hl_customers", HttpToolSpec.HttpMethod.GET, "/api/ai/customers", ToolPolicy.defaults());
        http("memory_save", HttpToolSpec.HttpMethod.POST, "/api/ai/memory", ToolPolicy.defaults());
        http("report_export", HttpToolSpec.HttpMethod.POST, "/api/ai/export", ToolPolicy.defaults());
        http("hl_toggle", HttpToolSpec.HttpMethod.PUT, "/api/ai/toggle",
                new ToolPolicy(Duration.ofSeconds(10), 0, false, true, false, "¿Detener la venta?",
                        false, false, false, false, true));
        // Flagged against the catalogue's rule, straight into the runtime: the
        // engine checks again and never offers it.
        http("hl_toggle_flagged", HttpToolSpec.HttpMethod.PUT, "/api/ai/toggle",
                new ToolPolicy(Duration.ofSeconds(10), 0, false, true, false, "¿Detener la venta?",
                        false, false, false, false, false, true));
        bean("hl_probe", "{\"type\":\"object\"}", headless());
        bean("hl_needs_text", "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},"
                + "\"required\":[\"text\"]}", headless());
        bean("hl_stepup", "{\"type\":\"object\"}", headless());
        catalog.registerCapability(new Capability(new Ids.CapabilityId("hl-cap"), "Operación",
                "Consultas y cambios del negocio", "Use the tools.",
                List.of("hl_me", "hl_sales", "hl_briefing", "hl_expired", "hl_customers", "memory_save",
                        "report_export", "hl_toggle", "hl_toggle_flagged", "hl_probe", "hl_needs_text",
                        "hl_stepup").stream().map(Ids.ToolId::new).toList(),
                List.of(), PermissionRequirement.none()));
    }

    void registerProfile(String tenantId, int maxToolRounds, Ids.StartFlowId startFlow) {
        profiles.register(new Profile(new Ids.ProfileId(PROFILE, 1), new Ids.TenantId(tenantId), "Dueño",
                "Hola.", "You are the operations assistant.",
                List.of(new Ids.CapabilityId("hl-cap")), List.of(), null, "es",
                Map.of("principalKinds", List.of(STAFF)), Map.of(), startFlow,
                new Profile.Runtime(null, maxToolRounds, true)));
    }

    static ToolPolicy headless() {
        return new ToolPolicy(Duration.ofSeconds(10), 0, true, false, false, null,
                false, false, false, false, false, true);
    }

    private void http(String id, HttpToolSpec.HttpMethod method, String url, ToolPolicy policy) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Agent-Channel", "{session.channel}");
        headers.put("X-Agent-Tool", id);
        catalog.registerTool(new ToolDescriptor(new Ids.ToolId(id),
                "{\"type\":\"object\"}", "{\"type\":\"object\"}", PermissionRequirement.none(),
                new ToolHandlerRef.Http(new HttpToolSpec(method, url, headers, null, null,
                        HttpToolSpec.AuthForwarding.PRINCIPAL_TOKEN)), policy));
    }

    private void bean(String id, String inputSchema, ToolPolicy policy) {
        catalog.registerTool(new ToolDescriptor(new Ids.ToolId(id), inputSchema, "{\"type\":\"object\"}",
                PermissionRequirement.none(), new ToolHandlerRef.Bean("headlessProbeTool"), policy));
    }

    // ---- tokens -------------------------------------------------------------

    /** The token the install signs each request with: the business's sender credential. */
    String serviceToken() {
        return serviceToken(serviceIss, "headless:run");
    }

    String serviceToken(String iss, String scope) {
        Date now = new Date();
        JWTClaimsSet.Builder b = new JWTClaimsSet.Builder().issuer(iss).audience("yaya-agentic")
                .subject("service:automations").issueTime(now)
                .expirationTime(new Date(now.getTime() + 60_000)).jwtID(UUID.randomUUID().toString());
        if (scope != null) b.claim("scope", scope);
        return sign(SERVICE_SECRET, b.build());
    }

    /** The run token: the responsible person's, an automation's, read-only, ten minutes. */
    String runToken(String subject) {
        return staffToken(subject, Map.of("via", "automation", "viaRef", "auto-1", "agentOnly", true,
                "viaReadOnly", true));
    }

    String staffToken(String subject, Map<String, Object> extra) {
        return staffToken(staffIss, business, subject, extra);
    }

    String staffToken(String iss, String orgId, String subject, Map<String, Object> extra) {
        Date now = new Date();
        JWTClaimsSet.Builder b = new JWTClaimsSet.Builder().issuer(iss).subject(subject)
                .claim("org", orgId).claim("username", "user-" + subject).issueTime(now)
                .expirationTime(new Date(now.getTime() + 600_000L)).jwtID(UUID.randomUUID().toString());
        extra.forEach(b::claim);
        return sign(STAFF_SECRET, b.build());
    }

    static String sign(String secret, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- requests -----------------------------------------------------------

    Map<String, Object> body(String principalToken, String question) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("profileId", PROFILE);
        body.put("principalToken", principalToken);
        body.put("question", question);
        body.put("locale", "es");
        body.put("timeZone", "America/Santo_Domingo");
        body.put("context", Map.of("automationId", "auto-1", "automationName", "Resumen del lunes",
                "executionId", "exec-1", "scheduledFor", "2026-11-16T12:00:00Z", "test", false));
        body.put("limits", Map.of("maxAnswerChars", 1800, "deadlineSeconds", 240));
        return body;
    }

    WebTestClient.ResponseSpec submit(String serviceToken, String idemKey, Object body) {
        WebTestClient.RequestBodySpec spec = client.post().uri("/v1/headless/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Nonce", UUID.randomUUID().toString())
                .header("X-Timestamp", String.valueOf(System.currentTimeMillis()));
        if (serviceToken != null) spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken);
        if (idemKey != null) spec = spec.header("X-Idem-Key", idemKey);
        return spec.bodyValue(body).exchange();
    }

    WebTestClient.ResponseSpec submit(Object body) {
        return submit(serviceToken(), "summary:" + UUID.randomUUID() + ":1", body);
    }

    /** Submits a run for a fresh person and returns its id. */
    String accepted(String question) {
        return accepted(body(runToken("u-" + UUID.randomUUID()), question));
    }

    String accepted(Map<String, Object> body) {
        Map<String, Object> answer = read(submit(body).expectStatus().isAccepted()
                .expectBody().returnResult().getResponseBody());
        return (String) answer.get("runId");
    }

    WebTestClient.ResponseSpec poll(String serviceToken, String runId) {
        return client.get().uri("/v1/headless/runs/" + runId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken)
                .header("X-Nonce", UUID.randomUUID().toString())
                .header("X-Timestamp", String.valueOf(System.currentTimeMillis()))
                .exchange();
    }

    /**
     * Reads a run. The service token lives a minute and the timestamp five: a
     * pause of the machine between minting and verifying (a suspended host, a
     * stalled container) makes one poll 401 or 409, which says nothing about
     * the run — so such a poll is repeated once with fresh credentials.
     */
    Map<String, Object> get(String runId) {
        var result = poll(serviceToken(), runId).expectBody().returnResult();
        int status = result.getStatus().value();
        if (status == 401 || status == 409) {
            return read(poll(serviceToken(), runId).expectStatus().isOk().expectBody().returnResult()
                    .getResponseBody());
        }
        assertThat(status).isEqualTo(200);
        return read(result.getResponseBody());
    }

    /** Polls until the run is completed or failed. */
    Map<String, Object> finished(String runId) {
        return finished(runId, 20_000);
    }

    Map<String, Object> finished(String runId, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            Map<String, Object> run = get(runId);
            Object status = run.get("status");
            if ("completed".equals(status) || "failed".equals(status)) return run;
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("run " + runId + " still " + status + " after " + timeoutMs + " ms");
            }
            sleep(40);
        }
    }

    /** Submits, waits for the end, returns the run. */
    Map<String, Object> runToEnd(String question) {
        return finished(accepted(question));
    }

    @SuppressWarnings("unchecked")
    static String failureCode(Map<String, Object> run) {
        Object failure = run.get("failure");
        return failure instanceof Map<?, ?> m ? (String) ((Map<String, Object>) m).get("code") : null;
    }

    String sessionOf(String runId) {
        return jdbc.queryForObject("SELECT session_id::text FROM headless_runs WHERE id = ?", String.class, runId);
    }

    Map<String, Object> read(byte[] body) {
        try {
            return json.readValue(body, MAP);
        } catch (Exception e) {
            throw new IllegalStateException("not JSON: " + (body == null ? null : new String(body)), e);
        }
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ---- the same profile, with a person present -------------------------

    /** An ordinary session of the same person on the same profile, as the panel opens it. */
    String startInteractive(String staffToken) {
        return (String) read(client.post().uri("/v1/sessions").contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + staffToken)
                .bodyValue(Map.of("tenant", tenant, "profileId", PROFILE, "channel", "admin-app"))
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody()).get("sessionId");
    }

    /** One message of that session; returns the raw event stream. */
    String sendInteractive(String sessionId, String staffToken, String text) {
        byte[] raw = client.post().uri("/v1/sessions/" + sessionId + "/messages")
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + staffToken)
                .bodyValue(Map.of("text", text))
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();
        return raw == null ? "" : new String(raw, StandardCharsets.UTF_8);
    }

    // ---- model script -------------------------------------------------------

    static LlmClient.LlmEvent.ToolUseProposal call(String id, String toolId, Map<String, Object> args) {
        return new LlmClient.LlmEvent.ToolUseProposal(id, toolId, args);
    }

    /** One model answer that proposes every call at once. */
    static Flux<LlmClient.LlmEvent> answer(LlmClient.LlmEvent.ToolUseProposal... calls) {
        List<LlmClient.LlmEvent> out = new ArrayList<>(List.of(calls));
        out.add(new LlmClient.LlmEvent.Done(LlmClient.StopReason.TOOL_USE, ScriptedLlmClient.ROUND_USAGE));
        return Flux.fromIterable(out);
    }

    /** The results the model was given for its last answer that called tools, by call id. */
    static Map<String, Object> resultsGiven(LlmClient.LlmRequest request) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (LlmClient.HistoryEntry entry : request.history()) {
            if (entry instanceof LlmClient.HistoryEntry.ToolResults r) {
                out.clear();
                r.results().forEach(res -> out.put(res.callId(), res.value()));
            }
        }
        return out;
    }

    static List<String> toolNames(LlmClient.LlmRequest request) {
        return request.availableTools().stream().map(LlmClient.ToolDefinition::name).toList();
    }

    // ---- beans --------------------------------------------------------------

    /**
     * A tool whose result is what its input asks for: a plain answer, or one
     * of the things that would stop an interactive turn and wait for a person.
     */
    public static class ProbeTool implements ToolHandler<Map<String, Object>, Map<String, Object>> {
        final List<Map<String, Object>> inputs = new CopyOnWriteArrayList<>();

        void reset() {
            inputs.clear();
        }

        @Override
        public Map<String, Object> execute(Map<String, Object> input, ExecutionContext ctx) {
            inputs.add(input);
            Map<String, Object> out = new LinkedHashMap<>();
            switch (String.valueOf(input.get("how"))) {
                case "confirm" -> out.put("_confirmRequest", Map.of("toolId", "hl_toggle",
                        "args", Map.of("station", "12"), "summary", "¿Detener la venta en la 12?"));
                case "code" -> {
                    out.put("ok", false);
                    out.put("reason", "verification_code_required");
                    out.put("_secretPrompt", Map.of("title", "Código", "message", "Escribe el código.",
                            "format", "digits", "length", 6, "expiresInSeconds", 300, "canResend", false));
                }
                case "hint" -> {
                    out.put("ok", true);
                    out.put("_uiHint", Map.of("kind", "automation", "payload", Map.of("draft", "draft-of-the-hint")));
                }
                default -> out.put("ok", true);
            }
            return out;
        }
    }

    /** Offers a step-up for {@code hl_stepup}, as a tenant's policy would for a sensitive read. */
    public static class StepUpForProbe implements Authorizer {
        @Override
        public AuthzDecision authorize(Principal principal, PermissionRequirement requirement, Object args,
                                       AuthzContext ctx) {
            Object tool = ctx == null || ctx.attributes() == null ? null : ctx.attributes().get("toolId");
            if (!"hl_stepup".equals(tool)) return AuthzDecision.Allow.none();
            return new AuthzDecision.DenyOfferStepUp("Necesito verificar que eres tú.", "step-up required",
                    new com.yayatechandinnovations.yayaagentic.auth.stepup.StepUpHint(
                            new com.yayatechandinnovations.yayaagentic.auth.stepup.StepUpMethod.OtpEmail(),
                            "sensitive-read", Duration.ofMinutes(5), Map.of()));
        }
    }

    /**
     * The real working memory, remembering every key and value that was ever
     * written for a session — a run's memory is cleared when the run ends, so
     * what was written during it can only be seen from here.
     */
    public static class RecordingWorkingMemory implements WorkingMemory {
        private final WorkingMemory real;
        private final Map<String, Map<String, Object>> written = new java.util.concurrent.ConcurrentHashMap<>();

        RecordingWorkingMemory(WorkingMemory real) {
            this.real = real;
        }

        void reset() {
            written.clear();
        }

        /** Everything written for the session, last value per key. */
        Map<String, Object> written(String sessionId) {
            return written.getOrDefault(sessionId, Map.of());
        }

        private void record(Ids.SessionId sessionId, Map<String, Object> values) {
            if (values == null) return;
            written.computeIfAbsent(sessionId.value(), k -> new java.util.concurrent.ConcurrentHashMap<>())
                    .putAll(values);
        }

        @Override
        public void set(Ids.SessionId sessionId, Map<String, Object> values) {
            record(sessionId, values);
            real.set(sessionId, values);
        }

        @Override
        public void merge(Ids.SessionId sessionId, Map<String, Object> values) {
            record(sessionId, values);
            real.merge(sessionId, values);
        }

        @Override public Map<String, Object> get(Ids.SessionId sessionId) { return real.get(sessionId); }
        @Override public void clear(Ids.SessionId sessionId) { real.clear(sessionId); }
        @Override public void remove(Ids.SessionId sessionId, String... keys) { real.remove(sessionId, keys); }
        @Override public boolean claim(Ids.SessionId sessionId, String token) { return real.claim(sessionId, token); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        @Primary
        RecordingWorkingMemory recordingWorkingMemory(
                com.yayatechandinnovations.yayaagentic.memory.RedisWorkingMemory real) {
            return new RecordingWorkingMemory(real);
        }

        @Bean
        @Primary
        ScriptedLlmClient scriptedLlmClient() {
            return new ScriptedLlmClient();
        }

        @Bean("headlessProbeTool")
        ProbeTool headlessProbeTool() {
            return new ProbeTool();
        }

        @Bean
        StepUpForProbe stepUpForProbe() {
            return new StepUpForProbe();
        }
    }
}
