package com.yayatechandinnovations.yayaagentic.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.core.Turn;
import com.yayatechandinnovations.yayaagentic.tenant.BaseUrlResolver;
import com.yayatechandinnovations.yayaagentic.tool.dispatch.HttpEgressPolicy;
import com.yayatechandinnovations.yayaagentic.tool.dispatch.HttpToolDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a tool argument, a claim or a session value can do to the request an
 * HTTP tool sends. A substituted value is data: it never changes the fields of
 * a JSON body, the address, or the header lines the tool's template declares.
 */
class HttpToolTemplateSafetyTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String TOGGLE_SCHEMA = """
            {"type":"object","properties":{"enabled":{"type":"boolean"},"reason":{"type":"string"}}}""";
    private static final String TOGGLE_BODY = "{\"enabled\":{enabled},\"reason\":\"{reason}\"}";

    private static final String LINES_SCHEMA = """
            {"type":"object","properties":{"ticketNumber":{"type":"string"},"linesJson":{"type":"string"}}}""";
    private static final String LINES_BODY = "{\"ticketNumber\":\"{ticketNumber}\",\"lines\":{linesJson}}";

    private static final String AMOUNT_SCHEMA = """
            {"type":"object","properties":{"amountDOP":{"type":"number"},"pointsAmountDOP":{"type":"number"},
             "lineNumber":{"type":"integer"},"methodType":{"type":"string"}}}""";

    private WireMockServer wireMock;

    @BeforeEach
    void start() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        wireMock.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));
    }

    @AfterEach
    void stop() {
        if (wireMock != null) wireMock.stop();
    }

    /* ------------------------- JSON body: inside a string ------------------------- */

    @Test
    void quotes_backslashes_and_line_breaks_in_a_string_argument_arrive_as_that_text() throws Exception {
        String reason = "El cliente dijo \"no\" \\ pidió\nreembolso\ty más";

        Turn.ToolResult result = post(TOGGLE_SCHEMA, TOGGLE_BODY, args("enabled", false, "reason", reason));

        assertThat(result.status()).isEqualTo(Turn.ToolResult.Status.OK);
        JsonNode sent = JSON.readTree(sentBody());
        assertThat(sent.get("reason").asText()).isEqualTo(reason);
        assertThat(sent.get("enabled").asBoolean()).isFalse();
        assertThat(sent.size()).isEqualTo(2);
    }

    @Test
    void a_string_argument_cannot_add_or_override_a_field_of_the_request() throws Exception {
        // The user confirmed "enabled: false". The reason tries to close its own
        // string and set the field again; a JSON parser keeps the last one.
        String reason = "ok\",\"enabled\":true,\"stationCode\":\"99";

        Turn.ToolResult result = post(TOGGLE_SCHEMA, TOGGLE_BODY, args("enabled", false, "reason", reason));

        assertThat(result.status()).isEqualTo(Turn.ToolResult.Status.OK);
        JsonNode sent = JSON.readTree(sentBody());
        assertThat(sent.get("enabled").asBoolean()).isFalse();
        assertThat(sent.has("stationCode")).isFalse();
        assertThat(sent.get("reason").asText()).isEqualTo(reason);
    }

    @Test
    void a_claim_or_session_value_inside_a_string_is_escaped_too() throws Exception {
        Principal p = new Principal("ali\"ce", new Ids.TenantId("default"), Set.of(),
                Map.of("phone", "+1809\",\"to\":\"+15550000"), Instant.now());
        ExecutionContext ctx = new ExecutionContext(p, new Ids.SessionId("s\"1"), new Ids.TurnId("t"),
                "trace", Map.of());

        Turn.ToolResult result = dispatch(tool("{\"type\":\"object\"}", HttpToolSpec.HttpMethod.POST, "/api/x",
                Map.of(), "{\"to\":\"{principal.claims.phone}\",\"by\":\"{principal.subject}\",\"s\":\"{session.id}\"}"),
                Map.of(), ctx);

        assertThat(result.status()).isEqualTo(Turn.ToolResult.Status.OK);
        JsonNode sent = JSON.readTree(sentBody());
        assertThat(sent.get("to").asText()).isEqualTo("+1809\",\"to\":\"+15550000");
        assertThat(sent.get("by").asText()).isEqualTo("ali\"ce");
        assertThat(sent.get("s").asText()).isEqualTo("s\"1");
        assertThat(sent.size()).isEqualTo(3);
    }

    /* --------------------------- JSON body: unquoted values --------------------------- */

    @Test
    void typed_numbers_and_booleans_are_sent_exactly_as_before() {
        Map<String, Object> a = args("amountDOP", 150.5, "pointsAmountDOP", 0, "lineNumber", 3, "methodType", "CASH");

        post(AMOUNT_SCHEMA, "{\"amountDOP\":{amountDOP},\"pointsAmountDOP\":{pointsAmountDOP},"
                + "\"lineNumber\":{lineNumber},\"methodType\":\"{methodType}\"}", a);

        assertThat(sentBody()).isEqualTo(
                "{\"amountDOP\":150.5,\"pointsAmountDOP\":0,\"lineNumber\":3,\"methodType\":\"CASH\"}");
    }

    @Test
    void an_absent_unquoted_value_is_null_instead_of_a_broken_body() {
        post(AMOUNT_SCHEMA, "{\"amountDOP\":{amountDOP},\"pointsAmountDOP\":{pointsAmountDOP}}",
                args("amountDOP", 20));

        assertThat(sentBody()).isEqualTo("{\"amountDOP\":20,\"pointsAmountDOP\":null}");
    }

    @Test
    void a_pre_serialised_json_argument_is_sent_as_given() {
        String lines = "[{\"numbers\":\"12\",\"amountDOP\":25,\"note\":\"dijo \\\"sí\\\"\"},{\"numbers\":\"07-45\"}]";

        Turn.ToolResult result = post(LINES_SCHEMA, LINES_BODY, args("ticketNumber", "T-1", "linesJson", lines));

        assertThat(result.status()).isEqualTo(Turn.ToolResult.Status.OK);
        assertThat(sentBody()).isEqualTo("{\"ticketNumber\":\"T-1\",\"lines\":" + lines + "}");
    }

    @Test
    void a_pre_serialised_object_is_sent_as_given() {
        String trigger = "{\"type\":\"TIME\",\"time\":\"21:00\",\"weekdays\":[\"lunes\"]}";

        post("{\"type\":\"object\",\"properties\":{\"triggerJson\":{\"type\":\"string\"}}}",
                "{\"trigger\":{triggerJson}}", args("triggerJson", trigger));

        assertThat(sentBody()).isEqualTo("{\"trigger\":" + trigger + "}");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[],\"paid\":true",                 // a second field after the value
            "[]}, {\"ticketNumber\":\"T-2\"",   // closes the body and opens another
            "[{\"numbers\":\"12\"",             // not finished
            "[1,2] // nota",                    // not JSON
            "['12']",                           // not JSON
            "{\"a\":1,\"a\":2}",                // one key twice
            "12",                               // a scalar where a list or object is expected
            "\"doce\"",
            "true",
            "null",
            "hola"
    })
    void a_pre_serialised_argument_that_is_not_one_json_object_or_array_is_refused(String lines) {
        Turn.ToolResult result = post(LINES_SCHEMA, LINES_BODY, args("ticketNumber", "T-1", "linesJson", lines));

        assertThat(result.status()).isEqualTo(Turn.ToolResult.Status.FAILED);
        assertThat(result.error()).contains("{linesJson}").contains("nothing was sent");
        wireMock.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void content_schema_narrows_a_pre_serialised_argument_to_one_type() {
        String schema = """
                {"type":"object","properties":{"ticketNumber":{"type":"string"},
                 "linesJson":{"type":"string","contentMediaType":"application/json","contentSchema":{"type":"array"}}}}""";

        Turn.ToolResult object = post(schema, LINES_BODY, args("ticketNumber", "T-1", "linesJson", "{\"a\":1}"));
        assertThat(object.status()).isEqualTo(Turn.ToolResult.Status.FAILED);
        assertThat(object.error()).contains("must be a JSON array").contains("JSON object was given");
        wireMock.verify(0, anyRequestedFor(anyUrl()));

        Turn.ToolResult array = post(schema, LINES_BODY, args("ticketNumber", "T-1", "linesJson", "[1]"));
        assertThat(array.status()).isEqualTo(Turn.ToolResult.Status.OK);
    }

    @Test
    void an_unquoted_number_slot_does_not_take_anything_but_a_number() {
        // No schema validation in front of the dispatcher here: a string in a
        // number slot is the worst case.
        Turn.ToolResult injected = post(AMOUNT_SCHEMA, "{\"amountDOP\":{amountDOP}}",
                args("amountDOP", "5,\"approved\":true"));
        assertThat(injected.status()).isEqualTo(Turn.ToolResult.Status.FAILED);

        Turn.ToolResult object = post(AMOUNT_SCHEMA, "{\"amountDOP\":{amountDOP}}",
                args("amountDOP", "{\"x\":1}"));
        assertThat(object.status()).isEqualTo(Turn.ToolResult.Status.FAILED);
        assertThat(object.error()).contains("must be a JSON number");
        wireMock.verify(0, anyRequestedFor(anyUrl()));

        Turn.ToolResult numeric = post(AMOUNT_SCHEMA, "{\"amountDOP\":{amountDOP}}", args("amountDOP", "75.25"));
        assertThat(numeric.status()).isEqualTo(Turn.ToolResult.Status.OK);
        assertThat(sentBody()).isEqualTo("{\"amountDOP\":75.25}");
    }

    @Test
    void an_undeclared_unquoted_value_must_still_be_exactly_one_json_value() {
        Turn.ToolResult injected = post("{\"type\":\"object\"}", "{\"org\":{org},\"n\":1}",
                args("org", "7,\"role\":\"admin\""));
        assertThat(injected.status()).isEqualTo(Turn.ToolResult.Status.FAILED);
        wireMock.verify(0, anyRequestedFor(anyUrl()));

        post("{\"type\":\"object\"}", "{\"org\":{org},\"n\":1}", args("org", "7"));
        assertThat(sentBody()).isEqualTo("{\"org\":7,\"n\":1}");
    }

    @Test
    void object_and_array_arguments_are_written_as_json() throws Exception {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("lines", List.of(Map.of("numbers", "12", "note", "dijo \"sí\"")));
        a.put("meta", Map.of("k", "v"));

        post("{\"type\":\"object\",\"properties\":{\"lines\":{\"type\":\"array\"},\"meta\":{\"type\":\"object\"}}}",
                "{\"lines\":{lines},\"meta\":{meta}}", a);

        JsonNode sent = JSON.readTree(sentBody());
        assertThat(sent.get("lines").get(0).get("note").asText()).isEqualTo("dijo \"sí\"");
        assertThat(sent.get("meta").get("k").asText()).isEqualTo("v");
    }

    @Test
    void a_value_that_is_only_part_of_an_unquoted_token_takes_plain_characters_only() {
        Turn.ToolResult ok = post("{\"type\":\"object\"}", "{\"amount\":-{n}}", args("n", "12.5"));
        assertThat(ok.status()).isEqualTo(Turn.ToolResult.Status.OK);
        assertThat(sentBody()).isEqualTo("{\"amount\":-12.5}");

        wireMock.resetRequests();
        Turn.ToolResult bad = post("{\"type\":\"object\"}", "{\"amount\":-{n}}", args("n", "1,\"x\":2"));
        assertThat(bad.status()).isEqualTo(Turn.ToolResult.Status.FAILED);
        wireMock.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void a_form_body_is_url_encoded() {
        ToolDescriptor tool = new ToolDescriptor(new Ids.ToolId("form"), "{\"type\":\"object\"}", "{\"type\":\"object\"}",
                PermissionRequirement.none(),
                new ToolHandlerRef.Http(new HttpToolSpec(HttpToolSpec.HttpMethod.POST, "/api/form", Map.of(),
                        new HttpToolSpec.BodyTemplate("application/x-www-form-urlencoded", "name={name}&n=1"),
                        new HttpToolSpec.ResponseProjection("$", Map.of()), HttpToolSpec.AuthForwarding.NONE)),
                ToolPolicy.defaults());

        dispatch(tool, args("name", "a&admin=true b"), ctx(Map.of()));

        assertThat(sentBody()).isEqualTo("name=a%26admin%3Dtrue%20b&n=1");
    }

    /* ------------------------------ context variables ------------------------------ */

    @Test
    void an_argument_cannot_stand_in_for_a_platform_variable() throws Exception {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("session", Map.of("id", "someone-else", "channel", "whatsapp"));
        a.put("turn", Map.of("id", "x"));
        a.put("call", Map.of("id", "x"));
        a.put("principal", Map.of("subject", "root", "claims", Map.of("org", "other-org")));
        a.put("principalToken", "stolen");

        dispatch(tool("{\"type\":\"object\"}", HttpToolSpec.HttpMethod.POST, "/api/x",
                Map.of("Authorization", "Bearer {principalToken}"),
                "{\"s\":\"{session.id}\",\"ch\":\"{session.channel}\",\"t\":\"{turn.id}\",\"c\":\"{call.id}\","
                        + "\"sub\":\"{principal.subject}\",\"org\":\"{principal.claims.org}\"}"),
                a, ctx(Map.of("channel", "app")));

        JsonNode sent = JSON.readTree(sentBody());
        assertThat(sent.get("s").asText()).isEqualTo("s");
        assertThat(sent.get("ch").asText()).isEqualTo("app");
        assertThat(sent.get("t").asText()).isEqualTo("t");
        assertThat(sent.get("c").asText()).isEqualTo("call-1");
        assertThat(sent.get("sub").asText()).isEqualTo("alice");
        assertThat(sent.get("org").asText()).isEqualTo("org-1");
        // No forwarding on this tool: the header is not sent at all, rather
        // than as a blank "Bearer ".
        assertThat(sentRequest().containsHeader("Authorization")).isFalse();
    }

    @Test
    void a_principal_token_header_is_filled_by_forwarding_and_never_logged_as_a_blank_value() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(HttpToolDispatcher.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs =
                new ch.qos.logback.core.read.ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            HttpToolSpec spec = new HttpToolSpec(HttpToolSpec.HttpMethod.GET, "/api/x?org={principal.claims.org}",
                    Map.of("Authorization", "Bearer {principalToken}", "X-Agent-Session", "{session.id}"),
                    null, new HttpToolSpec.ResponseProjection("$", Map.of()),
                    HttpToolSpec.AuthForwarding.PRINCIPAL_TOKEN);
            ToolDescriptor tool = new ToolDescriptor(new Ids.ToolId("safety"), "{\"type\":\"object\"}",
                    "{\"type\":\"object\"}", PermissionRequirement.none(), new ToolHandlerRef.Http(spec),
                    ToolPolicy.defaults());

            Turn.ToolResult result = dispatch(tool, args("principalToken", "stolen"),
                    ctx(Map.of("inboundAuthorization", "Bearer the-users-own-token")));

            assertThat(result.status()).isEqualTo(Turn.ToolResult.Status.OK);
            assertThat(sentRequest().getHeader("Authorization")).isEqualTo("Bearer the-users-own-token");
            assertThat(sentRequest().getHeader("X-Agent-Session")).isEqualTo("s");
            assertThat(logs.list)
                    .extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .noneMatch(message -> message.contains("resolved to blank"));
        } finally {
            logger.detachAppender(logs);
        }
    }

    @Test
    void an_optional_argument_left_out_is_still_logged_as_blank() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(HttpToolDispatcher.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs =
                new ch.qos.logback.core.read.ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            dispatch(tool("{\"type\":\"object\"}", HttpToolSpec.HttpMethod.GET, "/api/x?station={station}",
                    Map.of(), null), Map.of(), ctx(Map.of()));

            assertThat(logs.list)
                    .extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("station") && message.contains("resolved to blank"));
        } finally {
            logger.detachAppender(logs);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"app", "whatsapp", "sms", "chat"})
    void session_channel_is_available_in_url_header_and_body(String channel) throws Exception {
        dispatch(tool("{\"type\":\"object\"}", HttpToolSpec.HttpMethod.POST, "/api/x?via={session.channel}",
                Map.of("X-Agent-Channel", "{session.channel}"), "{\"channel\":\"{session.channel}\"}"),
                Map.of(), ctx(Map.of("channel", channel)));

        LoggedRequest sent = sentRequest();
        assertThat(sent.getUrl()).isEqualTo("/api/x?via=" + channel);
        assertThat(sent.getHeader("X-Agent-Channel")).isEqualTo(channel);
        assertThat(JSON.readTree(sent.getBodyAsString()).get("channel").asText()).isEqualTo(channel);
    }

    /* ----------------------------------- headers ----------------------------------- */

    @ParameterizedTest
    @ValueSource(strings = {"abc\r\nX-Org-Id: other-org", "abc\nX-Injected: 1", "abc\rdef", "abc\u0000def"})
    void a_header_value_with_a_line_break_or_control_character_is_refused(String value) {
        Turn.ToolResult result = dispatch(tool("{\"type\":\"object\"}", HttpToolSpec.HttpMethod.POST, "/api/x",
                Map.of("X-Note", "{note}"), "{}"), args("note", value), ctx(Map.of()));

        assertThat(result.status()).isEqualTo(Turn.ToolResult.Status.FAILED);
        assertThat(result.error()).contains("header 'X-Note'").contains("nothing was sent")
                .doesNotContain("other-org");
        wireMock.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void a_claim_with_a_line_break_does_not_reach_a_header() {
        Principal p = new Principal("alice", new Ids.TenantId("default"), Set.of(),
                Map.of("phone", "+1809\r\nX-Org-Id: other-org"), Instant.now());
        ExecutionContext ctx = new ExecutionContext(p, new Ids.SessionId("s"), new Ids.TurnId("t"), "trace", Map.of());

        Turn.ToolResult result = dispatch(tool("{\"type\":\"object\"}", HttpToolSpec.HttpMethod.POST, "/api/x",
                Map.of("X-Ai-Principal-Phone", "{principal.claims.phone}"), "{}"), Map.of(), ctx);

        assertThat(result.status()).isEqualTo(Turn.ToolResult.Status.FAILED);
        wireMock.verify(0, anyRequestedFor(anyUrl()));
    }

    /* ------------------------------------- URL ------------------------------------- */

    @Test
    void a_path_or_query_value_cannot_add_a_segment_a_parameter_or_a_fragment() {
        wireMock.stubFor(any(urlMatching("/api/tickets/.*")).willReturn(aResponse().withStatus(200).withBody("{}")));

        dispatch(tool("{\"type\":\"object\"}", HttpToolSpec.HttpMethod.GET,
                "/api/tickets/{ticketNumber}/undo?organizationId={principal.claims.org}&note={note}", Map.of(), null),
                args("ticketNumber", "T-1/../../admin?x=1#f", "note", "a&organizationId=other-org %41"),
                ctx(Map.of()));

        assertThat(sentRequest().getUrl()).isEqualTo(
                "/api/tickets/T-1%2F..%2F..%2Fadmin%3Fx%3D1%23f/undo"
                        + "?organizationId=org-1&note=a%26organizationId%3Dother-org%20%2541");
    }

    @ParameterizedTest
    @ValueSource(strings = {"..", "."})
    void a_path_value_of_dots_is_refused(String value) {
        Turn.ToolResult result = dispatch(tool("{\"type\":\"object\"}", HttpToolSpec.HttpMethod.GET,
                "/api/tickets/{ticketNumber}/undo", Map.of(), null), args("ticketNumber", value), ctx(Map.of()));

        assertThat(result.status()).isEqualTo(Turn.ToolResult.Status.FAILED);
        assertThat(result.error()).contains("{ticketNumber}");
        wireMock.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void dots_are_an_ordinary_query_value() {
        dispatch(tool("{\"type\":\"object\"}", HttpToolSpec.HttpMethod.GET, "/api/search?q={q}", Map.of(), null),
                args("q", ".."), ctx(Map.of()));

        assertThat(sentRequest().getUrl()).isEqualTo("/api/search?q=..");
    }

    /* ----------------------------------- helpers ----------------------------------- */

    private Turn.ToolResult post(String inputSchema, String bodyTemplate, Map<String, Object> args) {
        return dispatch(tool(inputSchema, HttpToolSpec.HttpMethod.POST, "/api/x", Map.of(), bodyTemplate),
                args, ctx(Map.of()));
    }

    private Turn.ToolResult dispatch(ToolDescriptor tool, Map<String, Object> args, ExecutionContext ctx) {
        YayaAgenticProperties props = new YayaAgenticProperties(
                "default", "default", null, null, null, null, null,
                new YayaAgenticProperties.HttpTools(List.of("localhost"), true, Duration.ofSeconds(3)),
                null, null, null);
        BaseUrlResolver resolver = new BaseUrlResolver(null, new ObjectMapper()) {
            @Override
            public URI resolve(Ids.TenantId t, String path, String override) {
                return URI.create("http://localhost:" + wireMock.port() + path);
            }
        };
        HttpToolDispatcher dispatcher = new HttpToolDispatcher(
                WebClient.builder(), new HttpEgressPolicy(props), resolver, new ObjectMapper(), props);
        return dispatcher.dispatch((ToolHandlerRef.Http) tool.handler(), tool, args, ctx, "call-1");
    }

    private static ToolDescriptor tool(String inputSchema, HttpToolSpec.HttpMethod method, String url,
                                       Map<String, String> headers, String bodyTemplate) {
        HttpToolSpec spec = new HttpToolSpec(method, url, headers,
                bodyTemplate == null ? null : new HttpToolSpec.BodyTemplate("application/json", bodyTemplate),
                new HttpToolSpec.ResponseProjection("$", Map.of()), HttpToolSpec.AuthForwarding.NONE);
        return new ToolDescriptor(new Ids.ToolId("safety"), inputSchema, "{\"type\":\"object\"}",
                PermissionRequirement.none(), new ToolHandlerRef.Http(spec), ToolPolicy.defaults());
    }

    private static ExecutionContext ctx(Map<String, Object> attributes) {
        Principal p = new Principal("alice", new Ids.TenantId("default"), Set.of(),
                Map.of("org", "org-1"), Instant.now());
        return new ExecutionContext(p, new Ids.SessionId("s"), new Ids.TurnId("t"), "trace", attributes);
    }

    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private LoggedRequest sentRequest() {
        List<LoggedRequest> all = wireMock.findAll(anyRequestedFor(anyUrl()));
        assertThat(all).isNotEmpty();
        return all.get(all.size() - 1);
    }

    private String sentBody() {
        return sentRequest().getBodyAsString();
    }
}
