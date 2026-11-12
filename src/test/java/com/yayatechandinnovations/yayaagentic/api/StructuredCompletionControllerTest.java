package com.yayatechandinnovations.yayaagentic.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatRateLimiter;
import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import com.yayatechandinnovations.yayaagentic.llm.StructuredLlmClient;
import com.yayatechandinnovations.yayaagentic.llm.StructuredLlmClient.StructuredRequest;
import com.yayatechandinnovations.yayaagentic.llm.StructuredLlmClient.StructuredResult;
import com.yayatechandinnovations.yayaagentic.llm.StubLlmClient;
import com.yayatechandinnovations.yayaagentic.structured.StructuredCompletionMetrics;
import com.yayatechandinnovations.yayaagentic.structured.StructuredCompletionService;
import com.yayatechandinnovations.yayaagentic.structured.StructuredCompletionTestSupport;
import com.yayatechandinnovations.yayaagentic.structured.StructuredCompletionsProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.yayatechandinnovations.yayaagentic.structured.StructuredCompletionTestSupport.ISS;
import static com.yayatechandinnovations.yayaagentic.structured.StructuredCompletionTestSupport.SECRET;
import static com.yayatechandinnovations.yayaagentic.structured.StructuredCompletionTestSupport.TENANT;
import static com.yayatechandinnovations.yayaagentic.structured.StructuredCompletionTestSupport.claims;
import static com.yayatechandinnovations.yayaagentic.structured.StructuredCompletionTestSupport.serviceToken;
import static com.yayatechandinnovations.yayaagentic.structured.StructuredCompletionTestSupport.sign;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code POST /v1/structured-completions} end to end over the real WebFlux
 * codecs: the real admission path (a real {@code SharedSecretJwtAuthenticator}
 * over an in-memory {@code tenant_jwt_secrets}), the real service, and either
 * the offline {@link StubLlmClient} or a scripted provider.
 */
class StructuredCompletionControllerTest {

    private static final String SCHEMA = """
            {"type":"object",
             "properties":{"permissionIds":{"type":"array","items":{"type":"string"}},
                           "summary":{"type":"string"}},
             "required":["permissionIds","summary"],
             "additionalProperties":false}""";

    private final StructuredCompletionTestSupport support =
            new StructuredCompletionTestSupport().trust(TENANT, ISS, SECRET);
    private final ChatRateLimiter rateLimiter = mock(ChatRateLimiter.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final StubLlmClient stub = new StubLlmClient();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void allowEverything() {
        when(rateLimiter.hit(any(), any(), anyInt(), any())).thenReturn(new ChatRateLimiter.Decision(true, 0));
    }

    // ---- Auth ------------------------------------------------------------

    @Test
    void no_token_is_401_and_never_reaches_the_model_even_when_anonymous_chat_is_allowed() {
        // allow-anonymous=true only feeds the AuthenticatorChain's fallback;
        // this endpoint does not consult the chain at all.
        Scripted provider = new Scripted("stub", r -> Map.of());
        WebTestClient client = client("stub", /* allowAnonymous */ true, provider);

        client.post().uri("/v1/structured-completions")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body("profile-builder", "x", SCHEMA))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.error").isEqualTo("unauthorized");

        assertThat(provider.requests).isEmpty();
        verify(rateLimiter, never()).hit(any(), any(), anyInt(), any());
    }

    @Test
    void an_api_key_or_a_forged_token_is_401() {
        WebTestClient client = client("stub", false, stub);

        post(client, "yk_live_abc12345xyz9q7w3e8r2t5y6u1i4o0p7a3", body("profile-builder", "x", SCHEMA))
                .expectStatus().isUnauthorized();
        post(client, sign("some-other-secret-that-is-long-enough-for-hs256", claims(ISS, "structured:complete").build()),
                body("profile-builder", "x", SCHEMA))
                .expectStatus().isUnauthorized();
    }

    @Test
    void a_valid_tenant_token_without_the_scope_is_403() {
        Scripted provider = new Scripted("stub", r -> Map.of());

        post(client("stub", false, provider), sign(SECRET, claims(ISS, "user.read").build()),
                body("profile-builder", "x", SCHEMA))
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.error").isEqualTo("forbidden");

        assertThat(provider.requests).isEmpty();
    }

    // ---- Validation --------------------------------------------------------

    @Test
    void bad_requests_are_400_invalid_request_with_a_detail() {
        WebTestClient client = client("stub", false, stub);
        String ok = "\"task\":\"profile-builder\",\"input\":\"x\",\"schema\":" + SCHEMA;

        List<String> bad = List.of(
                "not json",
                "[]",
                "{\"input\":\"x\",\"schema\":" + SCHEMA + "}",                                   // no task
                "{\"task\":\"Profile Builder\",\"input\":\"x\",\"schema\":" + SCHEMA + "}",       // task pattern
                "{\"task\":\"" + "a".repeat(65) + "\",\"input\":\"x\",\"schema\":" + SCHEMA + "}",
                "{\"task\":\"profile-builder\",\"schema\":" + SCHEMA + "}",                       // no input
                "{\"task\":\"profile-builder\",\"input\":\"  \",\"schema\":" + SCHEMA + "}",
                "{\"task\":\"profile-builder\",\"input\":\"" + "x".repeat(8001) + "\",\"schema\":" + SCHEMA + "}",
                "{" + ok + ",\"system\":\"" + "s".repeat(200_001) + "\"}",
                "{\"task\":\"profile-builder\",\"input\":\"x\"}",                                 // no schema
                "{\"task\":\"profile-builder\",\"input\":\"x\",\"schema\":{\"type\":\"array\"}}",
                "{\"task\":\"profile-builder\",\"input\":\"x\",\"schema\":\"object\"}",
                "{\"task\":\"profile-builder\",\"input\":\"x\",\"schema\":{\"type\":\"object\","
                        + "\"properties\":{\"a\":{\"$ref\":\"http://169.254.169.254/latest\"}}}}",
                "{" + ok + ",\"maxOutputTokens\":\"many\"}");

        for (String raw : bad) {
            post(client, serviceToken(), raw)
                    .expectStatus().isBadRequest()
                    .expectBody()
                    .jsonPath("$.error").isEqualTo("invalid_request")
                    .jsonPath("$.detail").isNotEmpty();
        }
        verify(rateLimiter, never()).hit(any(), any(), anyInt(), any());
    }

    @Test
    void max_output_tokens_defaults_to_1500_and_is_clamped() {
        Scripted provider = new Scripted("stub", r -> Map.of("permissionIds", List.of(), "summary", "s"));
        WebTestClient client = client("stub", false, provider);
        String base = "\"task\":\"profile-builder\",\"input\":\"x\",\"schema\":" + SCHEMA;

        post(client, serviceToken(), "{" + base + "}").expectStatus().isOk();
        post(client, serviceToken(), "{" + base + ",\"maxOutputTokens\":1}").expectStatus().isOk();
        post(client, serviceToken(), "{" + base + ",\"maxOutputTokens\":999999}").expectStatus().isOk();

        assertThat(provider.requests).extracting(StructuredRequest::maxOutputTokens)
                .containsExactly(1500, 64, 4096);
    }

    // ---- Happy path --------------------------------------------------------

    @Test
    void the_stub_provider_answers_offline_with_an_object_that_matches_the_schema() {
        post(client("stub", false, stub), serviceToken(), body("profile-builder", "puede vender y anular", SCHEMA))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.output.permissionIds").isArray()
                .jsonPath("$.output.summary").isEqualTo("")
                .jsonPath("$.usage.inputTokens").isNumber()
                .jsonPath("$.usage.outputTokens").isNumber()
                .jsonPath("$.model").isEqualTo("stub");
    }

    @Test
    void a_schema_declaring_an_older_standard_dialect_is_accepted() {
        String draft7 = "{\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"type\":\"object\","
                + "\"properties\":{\"n\":{\"type\":\"integer\",\"minimum\":1}},\"required\":[\"n\"]}";

        post(client("stub", false, stub), serviceToken(), body("profile-builder", "x", draft7))
                .expectStatus().isOk()
                .expectBody().jsonPath("$.output.n").isEqualTo(1);
    }

    @Test
    void the_model_is_called_once_with_the_system_prompt_the_input_and_the_schema_as_the_answer_tool()
            throws Exception {
        Scripted provider = new Scripted("stub",
                r -> Map.of("permissionIds", List.of("sales.sell", "sales.void"), "summary", "Vende y anula"));
        String system = "You map text to permission ids. Catalogue: sales.sell, sales.void";

        post(client("stub", false, provider), serviceToken(),
                json.writeValueAsString(Map.of("task", "profile-builder", "system", system,
                        "input", "puede vender y anular", "schema", json.readTree(SCHEMA), "maxOutputTokens", 800)))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.output.permissionIds[1]").isEqualTo("sales.void")
                .jsonPath("$.usage.inputTokens").isEqualTo(1234)
                .jsonPath("$.usage.outputTokens").isEqualTo(210)
                .jsonPath("$.model").isEqualTo("claude-test");

        assertThat(provider.requests).singleElement().satisfies(r -> {
            assertThat(r.system()).isEqualTo(system);
            assertThat(r.input()).isEqualTo("puede vender y anular");
            assertThat(r.toolName()).isEqualTo("emit_result");
            assertThat(json.readTree(r.schemaJson())).isEqualTo(json.readTree(SCHEMA));
            assertThat(r.maxOutputTokens()).isEqualTo(800);
            assertThat(r.retryHint()).isNull();
        });
        verify(rateLimiter).hit(eq("structured"), eq(TENANT), eq(30), eq(Duration.ofMinutes(1)));
        assertThat(meters.counter("structured_completions_total",
                "tenant", TENANT, "task", "profile-builder", "outcome", "ok").count()).isEqualTo(1);
        assertThat(meters.counter("structured_completion_input_tokens_total",
                "tenant", TENANT, "task", "profile-builder").count()).isEqualTo(1234);
        assertThat(meters.counter("structured_completion_output_tokens_total",
                "tenant", TENANT, "task", "profile-builder").count()).isEqualTo(210);
    }

    // ---- Schema violation → retry → 422 -------------------------------------

    @Test
    void a_schema_violation_is_retried_once_and_the_usage_of_both_calls_is_reported() {
        Scripted provider = new Scripted("stub",
                r -> r.retryHint() == null
                        ? Map.of("permissionIds", "sales.sell")                           // wrong type, no summary
                        : Map.of("permissionIds", List.of("sales.sell"), "summary", "ok"));

        post(client("stub", false, provider), serviceToken(), body("profile-builder", "x", SCHEMA))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.output.summary").isEqualTo("ok")
                .jsonPath("$.usage.inputTokens").isEqualTo(2468)
                .jsonPath("$.usage.outputTokens").isEqualTo(420);

        assertThat(provider.requests).hasSize(2);
        assertThat(provider.requests.get(1).retryHint()).contains("summary");
    }

    @Test
    void a_second_schema_violation_is_422_schema_violation() {
        Scripted provider = new Scripted("stub", r -> Map.of("permissionIds", List.of(), "extra", true));

        post(client("stub", false, provider), serviceToken(), body("profile-builder", "x", SCHEMA))
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.error").isEqualTo("schema_violation")
                .jsonPath("$.detail").isNotEmpty();

        assertThat(provider.requests).hasSize(2);
        assertThat(meters.counter("structured_completions_total",
                "tenant", TENANT, "task", "profile-builder", "outcome", "schema_violation").count()).isEqualTo(1);
        assertThat(meters.counter("structured_completion_input_tokens_total",
                "tenant", TENANT, "task", "profile-builder").count()).isEqualTo(2468);
    }

    @Test
    void a_model_that_never_calls_the_answer_tool_is_422_after_one_retry() {
        Scripted provider = new Scripted("stub", r -> null);

        post(client("stub", false, provider), serviceToken(), body("profile-builder", "x", SCHEMA))
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.error").isEqualTo("schema_violation");

        assertThat(provider.requests).hasSize(2);
    }

    // ---- Rate limit / provider errors ---------------------------------------

    @Test
    void over_the_tenant_rate_limit_is_429_with_retry_after_and_no_model_call() {
        when(rateLimiter.hit(eq("structured"), eq(TENANT), anyInt(), any()))
                .thenReturn(new ChatRateLimiter.Decision(false, 17));
        Scripted provider = new Scripted("stub", r -> Map.of());

        post(client("stub", false, provider), serviceToken(), body("profile-builder", "x", SCHEMA))
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals("Retry-After", "17")
                .expectBody().jsonPath("$.error").isEqualTo("rate_limited");

        assertThat(provider.requests).isEmpty();
    }

    @Test
    void a_provider_failure_is_502_upstream_failure() {
        Scripted provider = new Scripted("stub", r -> {
            throw new StructuredLlmClient.StructuredLlmException("boom", null);
        });

        post(client("stub", false, provider), serviceToken(), body("profile-builder", "x", SCHEMA))
                .expectStatus().isEqualTo(502)
                .expectBody()
                .jsonPath("$.error").isEqualTo("upstream_failure")
                .jsonPath("$.detail").doesNotExist();
    }

    @Test
    void a_provider_that_outlives_the_deadline_is_502_upstream_failure() {
        Scripted provider = new Scripted("stub", r -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Map.of("permissionIds", List.of(), "summary", "late");
        });
        WebTestClient client = client("stub", false, Duration.ofMillis(200), provider);

        post(client, serviceToken(), body("profile-builder", "x", SCHEMA))
                .expectStatus().isEqualTo(502)
                .expectBody().jsonPath("$.error").isEqualTo("upstream_failure");
    }

    @Test
    void a_provider_without_structured_support_is_503_llm_unavailable() {
        // provider=openai: only the stub and anthropic clients implement the capability.
        post(client("openai", false, stub), serviceToken(), body("profile-builder", "x", SCHEMA))
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.error").isEqualTo("llm_unavailable");
    }

    // ---- Fixture -------------------------------------------------------------

    /** A provider whose answers the test scripts; reports 1234 in / 210 out per call. */
    private static final class Scripted implements StructuredLlmClient {
        final String provider;
        final Function<StructuredRequest, Map<String, Object>> answer;
        final List<StructuredRequest> requests = new ArrayList<>();

        Scripted(String provider, Function<StructuredRequest, Map<String, Object>> answer) {
            this.provider = provider;
            this.answer = answer;
        }

        @Override
        public String provider() { return provider; }

        @Override
        public StructuredResult complete(StructuredRequest request) {
            requests.add(request);
            return new StructuredResult(answer.apply(request), false, 1234, 210, 1000, 0, "claude-test");
        }
    }

    private WebTestClient client(String provider, boolean allowAnonymous, StructuredLlmClient... clients) {
        return client(provider, allowAnonymous, null, clients);
    }

    private WebTestClient client(String provider, boolean allowAnonymous, Duration timeout,
                                 StructuredLlmClient... clients) {
        YayaAgenticProperties props = new YayaAgenticProperties(null, null, null, null,
                new YayaAgenticProperties.Llm(provider), null,
                new YayaAgenticProperties.Auth(allowAnonymous, null, null, null),
                null, null, null, null);
        StructuredCompletionService service = new StructuredCompletionService(List.of(clients), props,
                rateLimiter, new StructuredCompletionMetrics(meters),
                new StructuredCompletionsProperties(null, timeout), json);
        return WebTestClient.bindToController(new StructuredCompletionController(support.authenticator, service))
                .build();
    }

    private static WebTestClient.ResponseSpec post(WebTestClient client, String bearer, String rawBody) {
        return client.post().uri("/v1/structured-completions")
                .header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(rawBody)
                .exchange();
    }

    private static String body(String task, String input, String schema) {
        return "{\"task\":\"" + task + "\",\"input\":\"" + input + "\",\"schema\":" + schema + "}";
    }
}
