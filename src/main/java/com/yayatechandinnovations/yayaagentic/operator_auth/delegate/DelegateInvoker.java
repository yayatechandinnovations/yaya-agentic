package com.yayatechandinnovations.yayaagentic.operator_auth.delegate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.PathNotFoundException;
import com.jayway.jsonpath.ReadContext;
import com.yayatechandinnovations.yayaagentic.tool.dispatch.HttpEgressPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.net.URISyntaxException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Shared "execute the delegate + interpret the response" core. The same
 * code path runs in production (driving
 * {@code HttpDelegateOperatorAuthenticator}) and in the admin Test
 * button — so the operator's preview matches reality exactly.
 *
 * <p>See {@code docs/design/operator-auth-design.md} §5.1–§5.4 and §5.8.</p>
 */
@Component
public class DelegateInvoker {

    private static final Logger log = LoggerFactory.getLogger(DelegateInvoker.class);

    static final String HEADER_SOURCE = "X-Yaya-Source";
    static final String HEADER_SOURCE_SECRET = "X-Yaya-Source-Secret";
    static final String HEADER_ATTEMPT_ID = "X-Yaya-Attempt-Id";
    private static final Set<String> RESERVED_HEADERS_LOWER = Set.of(
            HEADER_SOURCE.toLowerCase(),
            HEADER_SOURCE_SECRET.toLowerCase(),
            HEADER_ATTEMPT_ID.toLowerCase());
    private static final String SOURCE_VALUE = "yaya-agentic";

    private static final Configuration JSONPATH_CFG = Configuration.defaultConfiguration()
            .addOptions(Option.SUPPRESS_EXCEPTIONS)
            .addOptions(Option.DEFAULT_PATH_LEAF_TO_NULL);
    private static final String REDACTED = "***";

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper json;
    private final HttpEgressPolicy egressPolicy;

    public DelegateInvoker(WebClient.Builder webClientBuilder, ObjectMapper json,
                           HttpEgressPolicy egressPolicy) {
        this.webClientBuilder = webClientBuilder;
        this.json = json;
        this.egressPolicy = egressPolicy;
    }

    public ProbeResult invoke(HttpDelegateConfig cfg, String username, char[] password, String attemptId) {
        if (cfg == null || !cfg.enabled() || isBlank(cfg.url())) {
            return new ProbeResult(
                    new SentRequest("", "", Map.of(), null),
                    new ReceivedResponse(null, 0, Map.of(), null, "delegate_disabled"),
                    new Evaluation(List.of(), null, Decision.DENY, "delegate_disabled"));
        }
        if (cfg.requireHttps() && !cfg.url().toLowerCase().startsWith("https://")) {
            return error("delegate_url_not_https", cfg, attemptId);
        }

        // SSRF guard: even though the URL is operator-configured, refuse
        // private/loopback/link-local addresses by default. Operators who
        // genuinely need a private target set allow-private-networks=true.
        URI uri;
        try {
            uri = new URI(cfg.url());
        } catch (URISyntaxException e) {
            return error("delegate_url_malformed", cfg, attemptId);
        }
        HttpEgressPolicy.Decision ed = egressPolicy.checkPrivateAddressOnly(uri);
        if (ed instanceof HttpEgressPolicy.Decision.Deny deny) {
            return error("delegate_egress_denied: " + deny.reason(), cfg, attemptId);
        }

        Built built = buildRequest(cfg, username, password, attemptId);
        long started = System.nanoTime();

        ReceivedResponse received;
        try {
            received = send(cfg, built);
        } catch (Exception e) {
            log.warn("operator-auth delegate POST failed: {}", e.toString());
            long ms = (System.nanoTime() - started) / 1_000_000;
            received = new ReceivedResponse(null, ms, Map.of(), null,
                    "delegate_unreachable: " + e.getClass().getSimpleName());
        }

        Evaluation eval = evaluate(cfg, received, username);
        return new ProbeResult(built.echo(), received, eval);
    }

    /** Private bundle: the redacted echo we return + the real body we send. */
    private record Built(SentRequest echo, String realBody) {}

    // ---- request construction ----------------------------------------

    private Built buildRequest(HttpDelegateConfig cfg, String username, char[] password, String attemptId) {
        RequestShape req = cfg.request();
        Map<String, String> headers = new LinkedHashMap<>();

        // Operator-supplied headers first (so reserved headers below can
        // overwrite any sneaky override). We also strip reserved-key
        // attempts here to make the audit log unambiguous.
        if (req.headers() != null) {
            req.headers().forEach((k, v) -> {
                if (k == null || RESERVED_HEADERS_LOWER.contains(k.toLowerCase())) {
                    log.warn("operator-auth delegate: ignoring operator-supplied reserved header '{}'", k);
                    return;
                }
                headers.put(k, CredentialTemplate.renderHeaderValue(v, username, password));
            });
        }

        headers.put(HEADER_SOURCE, SOURCE_VALUE);
        if (cfg.sharedSecret() != null) headers.put(HEADER_SOURCE_SECRET, cfg.sharedSecret());
        headers.put(HEADER_ATTEMPT_ID, attemptId == null ? "" : attemptId);

        String contentType = switch (req.body().format()) {
            case JSON -> MediaType.APPLICATION_JSON_VALUE;
            case FORM -> MediaType.APPLICATION_FORM_URLENCODED_VALUE;
            case BASIC_AUTH, NONE -> null;
        };
        if (contentType != null) headers.putIfAbsent("Content-Type", contentType);

        if (req.body().format() == RequestShape.BodyFormat.BASIC_AUTH) {
            // Auto-add the Basic Auth header (operator can still override
            // via a non-reserved Authorization template).
            headers.putIfAbsent("Authorization", CredentialTemplate.basicAuthHeader(username, password));
        }

        String realBody = CredentialTemplate.renderBody(
                req.body().format(), req.body().template(), username, password);
        // The echoed body has the password masked — it's what the Test
        // button shows and what would appear in any future audit dump.
        // realBody (with the real password) stays inside this object and
        // gets handed straight to the transport.
        String echoBody = redact(realBody, password);

        SentRequest echo = new SentRequest(req.method(), cfg.url(), headers, echoBody);
        return new Built(echo, realBody);
    }

    // ---- transport ---------------------------------------------------

    private ReceivedResponse send(HttpDelegateConfig cfg, Built built) {
        SentRequest sent = built.echo();
        WebClient client = webClientBuilder.build();
        WebClient.RequestBodySpec spec = client
                .method(org.springframework.http.HttpMethod.valueOf(sent.method()))
                .uri(sent.url());
        sent.headers().forEach(spec::header);

        WebClient.RequestHeadersSpec<?> request =
                built.realBody() == null ? spec : spec.bodyValue(built.realBody());

        long started = System.nanoTime();
        return request
                .exchangeToMono(resp -> resp.bodyToMono(String.class).defaultIfEmpty("")
                        .map(body -> {
                            long ms = (System.nanoTime() - started) / 1_000_000;
                            Map<String, String> headerMap = new LinkedHashMap<>();
                            resp.headers().asHttpHeaders().forEach((k, vs) -> {
                                if (!vs.isEmpty()) headerMap.put(k, vs.get(0));
                            });
                            return new ReceivedResponse(resp.statusCode().value(), ms,
                                    headerMap, body, null);
                        }))
                .timeout(cfg.timeout())
                .block(cfg.timeout().plus(Duration.ofSeconds(1)));
    }

    // ---- evaluation --------------------------------------------------

    Evaluation evaluate(HttpDelegateConfig cfg, ReceivedResponse received, String typedUsername) {
        List<CriterionCheck> checks = new ArrayList<>();

        if (received.error() != null) {
            checks.add(new CriterionCheck("transport", false, received.error()));
            return new Evaluation(checks, null, Decision.DENY, received.error());
        }

        SuccessCriteria suc = cfg.success();
        boolean allMatch = true;

        // status check
        boolean statusOk = suc.statusIn().contains(received.status());
        checks.add(new CriterionCheck(
                "statusIn",
                statusOk,
                "actual=" + received.status() + " configured=" + suc.statusIn()));
        if (!statusOk) allMatch = false;

        ReadContext parsed = parseBody(received.body());

        // exists check
        if (!isBlank(suc.jsonPathExists())) {
            boolean exists = pathExists(parsed, suc.jsonPathExists());
            checks.add(new CriterionCheck(
                    "jsonPathExists",
                    exists,
                    suc.jsonPathExists()));
            if (!exists) allMatch = false;
        }

        // equals checks
        if (suc.jsonPathEquals() != null) {
            for (SuccessCriteria.JsonPathEquals eq : suc.jsonPathEquals()) {
                Object actual = readPath(parsed, eq.path());
                boolean matched = jsonValuesEqual(actual, eq.value());
                checks.add(new CriterionCheck(
                        "jsonPathEquals",
                        matched,
                        eq.path() + " expected=" + render(eq.value()) + " actual=" + render(actual)));
                if (!matched) allMatch = false;
            }
        }

        if (!allMatch) {
            String reason = extractFailureReason(cfg, parsed);
            return new Evaluation(checks, null, Decision.DENY,
                    reason != null ? reason : "success_criteria_unmet");
        }

        // identity extraction
        IdentityMapping idm = cfg.identity();
        String subject;
        if (isBlank(idm.subjectPath())) {
            subject = typedUsername;
        } else {
            Object extracted = readPath(parsed, idm.subjectPath());
            if (extracted == null || extracted instanceof Map || extracted instanceof List) {
                return new Evaluation(checks, null, Decision.DENY, "identity_extraction_failed");
            }
            subject = extracted.toString();
        }

        String displayName = subject;
        if (!isBlank(idm.displayNamePath())) {
            Object d = readPath(parsed, idm.displayNamePath());
            if (d != null && !(d instanceof Map) && !(d instanceof List)) {
                displayName = d.toString();
            }
        }

        Map<String, Object> attrs = Map.of();
        if (!isBlank(idm.attributesPath())) {
            Object a = readPath(parsed, idm.attributesPath());
            if (a instanceof Map<?, ?> map) {
                LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
                map.forEach((k, v) -> copy.put(String.valueOf(k), v));
                attrs = copy;
            }
        }

        ExtractedIdentity identity = new ExtractedIdentity(subject, displayName, attrs);
        return new Evaluation(checks, identity, Decision.ALLOW, null);
    }

    private String extractFailureReason(HttpDelegateConfig cfg, ReadContext parsed) {
        String path = cfg.failure() == null ? null : cfg.failure().reasonPath();
        if (isBlank(path) || parsed == null) return null;
        Object v = readPath(parsed, path);
        return v == null ? null : v.toString();
    }

    // ---- JSONPath plumbing -------------------------------------------

    private ReadContext parseBody(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            return JsonPath.using(JSONPATH_CFG).parse(body);
        } catch (Exception e) {
            return null;
        }
    }

    private Object readPath(ReadContext ctx, String path) {
        if (ctx == null || isBlank(path)) return null;
        try {
            return ctx.read(path);
        } catch (PathNotFoundException pne) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean pathExists(ReadContext ctx, String path) {
        if (ctx == null || isBlank(path)) return false;
        return readPath(ctx, path) != null;
    }

    private static boolean jsonValuesEqual(Object actual, Object expected) {
        if (expected == null) return actual == null;
        if (actual == null) return false;
        if (expected instanceof Number en && actual instanceof Number an) {
            return Double.compare(en.doubleValue(), an.doubleValue()) == 0;
        }
        if (expected instanceof Boolean || actual instanceof Boolean) {
            return Objects.equals(String.valueOf(expected), String.valueOf(actual));
        }
        return Objects.equals(expected.toString(), actual.toString());
    }

    private String render(Object v) {
        if (v == null) return "null";
        try {
            return json.writeValueAsString(v);
        } catch (Exception e) {
            return String.valueOf(v);
        }
    }

    // ---- misc --------------------------------------------------------

    private ProbeResult error(String auditReason, HttpDelegateConfig cfg, String attemptId) {
        return new ProbeResult(
                new SentRequest(cfg.request().method(), cfg.url(), Map.of(), null),
                new ReceivedResponse(null, 0, Map.of(), null, auditReason),
                new Evaluation(List.of(new CriterionCheck("preflight", false, auditReason)),
                        null, Decision.DENY, auditReason));
    }

    private static String redact(String body, char[] password) {
        if (body == null || password == null || password.length == 0) return body;
        String pw = new String(password);
        if (pw.isEmpty()) return body;
        return body.replace(pw, REDACTED);
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    // ---- output records ----------------------------------------------

    public record ProbeResult(SentRequest request, ReceivedResponse response, Evaluation evaluation) {
        public boolean allowed() { return evaluation.decision() == Decision.ALLOW; }
        public String auditReason() { return evaluation.auditReason(); }
    }

    public record SentRequest(String method, String url, Map<String, String> headers, String body) {}

    public record ReceivedResponse(Integer status, long durationMs,
                                   Map<String, String> headers, String body, String error) {}

    public record Evaluation(List<CriterionCheck> successChecks,
                             ExtractedIdentity identity,
                             Decision decision,
                             String auditReason) {}

    public record CriterionCheck(String criterion, boolean matched, String detail) {}

    public record ExtractedIdentity(String subject, String displayName, Map<String, Object> attributes) {}

    public enum Decision { ALLOW, DENY }
}
