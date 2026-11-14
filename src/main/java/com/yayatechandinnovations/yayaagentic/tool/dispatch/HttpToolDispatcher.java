package com.yayatechandinnovations.yayaagentic.tool.dispatch;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.io.JsonStringEncoder;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.core.Turn;
import com.yayatechandinnovations.yayaagentic.core.TurnChannel;
import com.yayatechandinnovations.yayaagentic.tenant.BaseUrlResolutionException;
import com.yayatechandinnovations.yayaagentic.tenant.BaseUrlResolver;
import com.yayatechandinnovations.yayaagentic.tool.HttpToolSpec;
import com.yayatechandinnovations.yayaagentic.tool.ToolDescriptor;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandlerRef;
import com.yayatechandinnovations.yayaagentic.tool.ToolPolicy;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriUtils;
import reactor.util.retry.Retry;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP-backed tool dispatch. Renders URL + body templates, applies
 * {@link HttpToolSpec.AuthForwarding} explicitly, runs the request via
 * {@link WebClient}, projects the response back into the tool's output
 * schema, and surfaces any HTTP / network / projection error as a FAILED
 * tool result. Every call passes through {@link HttpEgressPolicy} first.
 */
@Component
public class HttpToolDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(HttpToolDispatcher.class);

    /** Matches {field} or {a.b.c} placeholders in URL / header / body templates. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z0-9_.]+)\\}");

    /** Sentinel a stale own-scoped token surfaces on a self-healing 409:
     *  the response header {@code X-Yaya-Token-Refresh: required} and/or the
     *  body {@code {"error":"token_refresh_required"}}. */
    private static final String TOKEN_REFRESH_HEADER = "X-Yaya-Token-Refresh";
    private static final String TOKEN_REFRESH_REQUIRED = "token_refresh_required";

    private final WebClient.Builder webClientBuilder;
    private final HttpEgressPolicy egress;
    private final BaseUrlResolver baseUrlResolver;
    private final ObjectMapper json;
    private final Duration defaultTimeout;
    private final Map<String, byte[]> serviceTokenSecrets;
    /** Re-mints an upgraded principal token when a player registers mid-session.
     *  Null in unit tests / deployments without the channel-auth stack. */
    private final SessionTokenRefresher tokenRefresher;

    /** Test-friendly constructor — no token refresh (guest→registered upgrade
     *  is a channel-auth concern the tool layer only collaborates with). */
    public HttpToolDispatcher(WebClient.Builder webClientBuilder,
                              HttpEgressPolicy egress,
                              BaseUrlResolver baseUrlResolver,
                              ObjectMapper json,
                              YayaAgenticProperties props) {
        this(webClientBuilder, egress, baseUrlResolver, json, props, (SessionTokenRefresher) null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public HttpToolDispatcher(WebClient.Builder webClientBuilder,
                              HttpEgressPolicy egress,
                              BaseUrlResolver baseUrlResolver,
                              ObjectMapper json,
                              YayaAgenticProperties props,
                              SessionTokenRefresher tokenRefresher) {
        this.webClientBuilder = webClientBuilder;
        this.egress = egress;
        this.baseUrlResolver = baseUrlResolver;
        this.json = json;
        this.tokenRefresher = tokenRefresher;
        this.defaultTimeout = props.httpTools() != null && props.httpTools().defaultTimeout() != null
                ? props.httpTools().defaultTimeout()
                : Duration.ofSeconds(10);
        Map<String, byte[]> secrets = new HashMap<>();
        if (props.auth() != null && props.auth().serviceToken() != null
                && props.auth().serviceToken().tenantSecrets() != null) {
            props.auth().serviceToken().tenantSecrets().forEach((tenant, secret) -> {
                if (secret != null && !secret.isBlank()) {
                    secrets.put(tenant, secret.getBytes(StandardCharsets.UTF_8));
                }
            });
        }
        this.serviceTokenSecrets = Map.copyOf(secrets);
    }

    public Turn.ToolResult dispatch(ToolHandlerRef.Http handlerRef,
                                    ToolDescriptor descriptor,
                                    Map<String, Object> args,
                                    ExecutionContext ctx,
                                    String callId) {
        HttpToolSpec spec = handlerRef.spec();
        Map<String, Object> richArgs = enrichArgs(args, ctx, callId);
        String toolId = descriptor.id().value();

        URI url;
        try {
            String renderedPath = renderUrlTemplate(spec.urlTemplate(), richArgs, toolId);
            String override = ctx == null || ctx.attributes() == null ? null
                    : (String) ctx.attributes().get("hostBaseUrlOverride");
            url = baseUrlResolver.resolve(
                    ctx == null || ctx.principal() == null ? null : ctx.principal().tenant(),
                    renderedPath,
                    override);
        } catch (TemplateRenderException e) {
            return failed(callId, e.getMessage());
        } catch (BaseUrlResolutionException e) {
            return failed(callId, "egress: " + e.getMessage());
        } catch (Exception e) {
            return failed(callId, "HTTP tool URL render failed: " + e.getMessage());
        }

        HttpEgressPolicy.Decision egressDecision = egress.check(url);
        if (egressDecision instanceof HttpEgressPolicy.Decision.Deny deny) {
            return failed(callId, deny.reason());
        }

        Map<String, String> headers = new LinkedHashMap<>();
        String body = null;
        // The secret the user typed for this one call, if the engine attached
        // one. It is only ever offered to header templates: an address or a
        // body is echoed in error texts and diagnostics, a header is not.
        Map<String, Object> secretArgs = withSecret(richArgs, ctx);
        try {
            if (spec.headerTemplates() != null) {
                spec.headerTemplates().forEach((k, v) -> {
                    // {principalToken} is not a template variable: the user's
                    // token only ever travels through authForwarding below. A
                    // header written with it (the usual "Bearer {principalToken}")
                    // is left to that step instead of being rendered as a blank
                    // credential — and logged as a missing value — first.
                    if (referencesPrincipalToken(v)) return;
                    if (referencesSecret(v)) {
                        // {secret.value} exists for one request and only in a
                        // header. Without a value the header is left out rather
                        // than sent empty.
                        String rendered = renderTemplate(v, secretArgs, toolId);
                        if (!rendered.isBlank()) headers.put(k, rendered);
                        return;
                    }
                    headers.put(k, renderTemplate(v, richArgs, toolId));
                });
            }
            if (spec.body() != null && spec.body().template() != null
                    && spec.method() != HttpToolSpec.HttpMethod.GET
                    && spec.method() != HttpToolSpec.HttpMethod.DELETE) {
                body = renderBody(spec.body(), richArgs, descriptor);
            }
        } catch (TemplateRenderException e) {
            return failed(callId, e.getMessage());
        }
        // A bound-action tap (whatsapp-ops-integration-design.md §6.4) pins the
        // key to the action, so a repeat tap in a later turn is the SAME request
        // to the system of record, whatever the tool's own template renders.
        Object pinnedKey = ctx == null || ctx.attributes() == null ? null : ctx.attributes().get("idempotencyKey");
        if (pinnedKey != null) {
            headers.keySet().removeIf(k -> k.equalsIgnoreCase("Idempotency-Key"));
            headers.put("Idempotency-Key", String.valueOf(pinnedKey));
        }
        applyAuthForwarding(spec.authForwarding(), ctx, headers);
        // A header value with a line break would let an argument or a claim
        // add its own header lines to the request. Never name the value: it
        // may be a credential.
        for (Map.Entry<String, String> h : headers.entrySet()) {
            if (hasControlChars(h.getValue())) {
                return failed(callId, "HTTP tool '" + toolId + "' header '" + h.getKey()
                        + "' would carry a line break or control character — nothing was sent.");
            }
        }
        // Diagnostics for the playground auth-flow inspector: which forwarding
        // mode this tool uses and whether an auth header actually got attached.
        // A PRINCIPAL_TOKEN tool with authHeaderPresent=false is the smoking gun
        // for a 401 (no token was forwarded). See
        // playground-auth-flow-inspector-design.md §6.3.
        Map<String, Object> diag = authDiagnostics(spec.authForwarding(), headers);

        WebClient client = webClientBuilder.build();
        ToolPolicy policy = descriptor.policy() == null ? ToolPolicy.defaults() : descriptor.policy();
        Duration timeout = policy.timeout() == null ? defaultTimeout : policy.timeout();
        // A request that carries the user's code goes out once, whatever the
        // tool's retry setting: a repeat after a timeout or a 5xx could spend
        // another attempt of the code, or apply the change a second time. (A
        // 409 that asks for a fresh token is not a repeat of a request the
        // backend acted on: it refused it before reading it.)
        int retries = secretArgs != richArgs ? 0 : policy.maxRetries();

        boolean ownScoped = spec.authForwarding() == HttpToolSpec.AuthForwarding.PRINCIPAL_TOKEN;
        try {
            String responseBody;
            try {
                responseBody = sendOnce(client, spec, url, headers, body, retries, timeout);
            } catch (WebClientResponseException e) {
                // Signal 1 — self-healing 409: the own-scoped token is stale
                // because the caller registered mid-session and this token was
                // minted before the PLAYER_QUERY_OWN upgrade. Re-mint once and
                // retry exactly once. A second 409 surfaces as a normal error —
                // the single retry is the guard against refresh→409→refresh.
                if (ownScoped && isTokenRefreshRequired(e) && tokenRefresher != null) {
                    Optional<String> refreshed = tokenRefresher.refresh(ctx);
                    if (refreshed.isPresent()) {
                        headers.put(HttpHeaders.AUTHORIZATION, refreshed.get());
                        diag.put("tokenRefreshed", true);
                        try {
                            responseBody = sendOnce(client, spec, url, headers, body, retries, timeout);
                        } catch (WebClientResponseException retryErr) {
                            diag.put("httpStatus", retryErr.getStatusCode().value());
                            return failed(callId, formatHttpFailure(retryErr)).withDiagnostics(diag);
                        }
                        return project(callId, spec.response(), responseBody).withDiagnostics(diag);
                    }
                }
                throw e;
            }
            // Signal 2 — registration fast-path: a successful result that flags
            // tokenRefreshRequired means the principal just upgraded; proactively
            // re-mint so the NEXT own-scoped call skips the 409 round-trip.
            maybeProactiveRefresh(ctx, responseBody, diag);
            return project(callId, spec.response(), responseBody).withDiagnostics(diag);
        } catch (WebClientResponseException e) {
            diag.put("httpStatus", e.getStatusCode().value());
            return failed(callId, formatHttpFailure(e)).withDiagnostics(diag);
        } catch (Exception e) {
            Throwable root = rootCause(e);
            if (root instanceof WebClientResponseException w) {
                diag.put("httpStatus", w.getStatusCode().value());
            }
            return failed(callId, "HTTP tool '" + descriptor.id().value() + "' failed: "
                    + describeCause(e)).withDiagnostics(diag);
        }
    }

    /** One request attempt. Transient failures (5xx/429/IO/timeout) still retry
     *  {@code maxRetries} times ({@link ToolPolicy}; zero when the request carries
     *  a user-typed secret); everything else — including a token-refresh 409 —
     *  throws {@link WebClientResponseException} for the caller to classify. */
    private String sendOnce(WebClient client, HttpToolSpec spec, URI url,
                            Map<String, String> headers, String body,
                            int maxRetries, Duration timeout) {
        WebClient.RequestBodySpec req = client
                .method(HttpMethod.valueOf(spec.method().name()))
                .uri(url)
                .headers(h -> headers.forEach(h::set));
        if (body != null) {
            String contentType = spec.body().contentType() != null
                    ? spec.body().contentType() : MediaType.APPLICATION_JSON_VALUE;
            req.header(HttpHeaders.CONTENT_TYPE, contentType);
            req.bodyValue(body);
        }
        var mono = req.retrieve().bodyToMono(String.class).timeout(timeout);
        if (maxRetries > 0) {
            // Only retry transient failures. Retrying a 404 / 401 / 409 / 422
            // just wastes time and floods the logs with "Retries exhausted"
            // wrappers that hide the real cause.
            mono = mono.retryWhen(Retry.backoff(maxRetries, Duration.ofMillis(200))
                    .filter(HttpToolDispatcher::isTransient));
        }
        return mono.block();
    }

    /**
     * Signal 1 detector: a stale-token 409 the agent must self-heal, as opposed
     * to a 403 (genuine authorization denial — caller is not registered; do NOT
     * refresh) or a 401 (unauthenticated). Recognised by the
     * {@code X-Yaya-Token-Refresh: required} header OR a
     * {@code {"error":"token_refresh_required"}} body.
     */
    private boolean isTokenRefreshRequired(WebClientResponseException e) {
        if (e.getStatusCode().value() != 409) return false;
        String hdr = e.getHeaders().getFirst(TOKEN_REFRESH_HEADER);
        if (hdr != null && "required".equalsIgnoreCase(hdr.trim())) return true;
        return errorFieldEquals(e.getResponseBodyAsString(), TOKEN_REFRESH_REQUIRED);
    }

    /** Signal 2: re-mint proactively when a tool result flags
     *  {@code tokenRefreshRequired}, unless the current token's
     *  {@code accountState} claim is already {@code registered} (nothing to
     *  upgrade — skip the extra round-trip). */
    private void maybeProactiveRefresh(ExecutionContext ctx, String responseBody,
                                       Map<String, Object> diag) {
        if (tokenRefresher == null || responseBody == null || responseBody.isBlank()) return;
        if (!bodyFlagsTokenRefreshRequired(responseBody)) return;
        if (accountStateRegistered(ctx)) return;
        tokenRefresher.refresh(ctx).ifPresent(h -> diag.put("tokenRefreshed", true));
    }

    private boolean errorFieldEquals(String rawBody, String expected) {
        if (rawBody == null || rawBody.isBlank()) return false;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = json.readValue(rawBody, Map.class);
            return expected.equals(parsed.get("error"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean bodyFlagsTokenRefreshRequired(String rawBody) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = json.readValue(rawBody, Map.class);
            return Boolean.TRUE.equals(parsed.get("tokenRefreshRequired"))
                    || "true".equals(String.valueOf(parsed.get("tokenRefreshRequired")));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean accountStateRegistered(ExecutionContext ctx) {
        if (ctx == null || ctx.principal() == null || ctx.principal().claims() == null) return false;
        Object state = ctx.principal().claims().get("accountState");
        return state != null && "registered".equalsIgnoreCase(state.toString());
    }

    /** Auth-forwarding diagnostics for the inspector — the mode and whether an
     *  auth header was actually attached after {@link #applyAuthForwarding}. */
    private static Map<String, Object> authDiagnostics(HttpToolSpec.AuthForwarding mode,
                                                       Map<String, String> headers) {
        HttpToolSpec.AuthForwarding m = mode == null ? HttpToolSpec.AuthForwarding.NONE : mode;
        boolean present = switch (m) {
            case PRINCIPAL_TOKEN -> headers.containsKey(HttpHeaders.AUTHORIZATION);
            case SERVICE_TOKEN -> headers.containsKey("X-Yaya-Service-Token");
            case NONE -> false;
        };
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("authForwarding", m.name());
        d.put("authHeaderPresent", present);
        return d;
    }

    /** Retry only what's worth retrying. Everything else fails fast. */
    private static boolean isTransient(Throwable t) {
        if (t instanceof WebClientResponseException w) {
            int code = w.getStatusCode().value();
            return code >= 500 || code == 429;
        }
        return t instanceof java.io.IOException
                || t instanceof java.util.concurrent.TimeoutException;
    }

    /**
     * Unwrap Reactor's {@code RetryExhaustedException} (and any other
     * decorator) so the FAILED tool_result carries the actual reason —
     * HTTP status, "Connection refused", "read timeout", etc. — instead of
     * the opaque {@code "Retries exhausted: N/N"} text.
     */
    private static String describeCause(Throwable t) {
        Throwable root = rootCause(t);
        if (root instanceof WebClientResponseException w) return formatHttpFailure(w);
        String msg = root.getMessage();
        String name = root.getClass().getSimpleName();
        return (msg == null || msg.isBlank()) ? name : name + ": " + msg;
    }

    private static Throwable rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        return cur;
    }

    private static String formatHttpFailure(WebClientResponseException e) {
        String body = e.getResponseBodyAsString();
        String base = "HTTP " + e.getStatusCode().value() + " " + e.getStatusText();
        if (body == null || body.isBlank()) return base;
        // Cap body in the error string — tool results bubble back to the LLM
        // and a 50 KB error page in the prompt is not useful.
        String snippet = body.length() > 500 ? body.substring(0, 500) + "…" : body;
        return base + " — " + snippet;
    }

    // ---- Template + projection helpers ----------------------------------

    private String renderTemplate(String template, Map<String, Object> args, String toolId) {
        return render(template, args, toolId, false);
    }

    /**
     * Like {@link #renderTemplate} but percent-encodes each substituted value
     * so LLM-supplied arguments are URL-safe. Without this, a value like
     * {@code "Palé"} or one containing a space goes onto the wire as raw bytes
     * and the receiving server rejects the request line per RFC 7230/3986
     * ("Invalid character found in the request target"). The template's own
     * structural characters ({@code ? & = /}) are left untouched — only the
     * interpolated values are encoded, so a value can never add a path
     * segment, a query parameter or a fragment. A path value of {@code .} or
     * {@code ..} is refused: percent-encoding leaves dots alone and the
     * receiving server would resolve them to a different resource.
     */
    private String renderUrlTemplate(String template, Map<String, Object> args, String toolId) {
        return render(template, args, toolId, true);
    }

    private String render(String template, Map<String, Object> args, String toolId, boolean urlEncodeValues) {
        if (template == null) return null;
        int queryStart = template.indexOf('?');
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String path = m.group(1);
            Object value = resolve(path, args, toolId);
            String rendered = value == null ? "" : value.toString();
            if (urlEncodeValues && !rendered.isEmpty()) {
                boolean inPath = queryStart < 0 || m.start() < queryStart;
                if (inPath && (rendered.equals(".") || rendered.equals(".."))) {
                    throw new TemplateRenderException("HTTP tool '" + toolId + "' value for {" + path
                            + "} is not a valid identifier for the address — nothing was sent.");
                }
                rendered = UriUtils.encode(rendered, StandardCharsets.UTF_8);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(rendered));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** The value behind one placeholder, with the blank-context guard. */
    private Object resolve(String path, Map<String, Object> args, String toolId) {
        Object value = pluck(args, path);
        boolean blank = value == null || value.toString().isBlank();
        if (blank && path.startsWith("principal.")) {
            // Server-verified context (organizationId lives at
            // principal.claims.org) is platform-guaranteed, never an
            // optional LLM filter. A blank one means the player has no
            // resolved value — refuse to send it rather than emit e.g.
            // `?organizationId=`, which the backend reads as "empty scope"
            // and answers with an empty list. Fail loud so the misconfig
            // has a fingerprint instead of masquerading as "no data".
            throw new MissingContextException(
                    "HTTP tool '" + toolId + "' requires template value {" + path
                            + "} but it is blank for the current principal — the player has no "
                            + "resolved value for this scope (check the auth-resolver JWT claims). "
                            + "Refusing to send a blank value.");
        }
        if (blank && path.startsWith(SECRET_NAME + ".")) {
            // No secret is attached to most calls; that is not a missing argument.
            return null;
        }
        if (blank) {
            // Optional LLM-supplied arg absent — make the miss visible in logs.
            LOG.warn("template placeholder {{{}}} resolved to blank in toolId={}", path, toolId);
        }
        return value;
    }

    // ---- Body rendering ---------------------------------------------------

    private String renderBody(HttpToolSpec.BodyTemplate bodySpec, Map<String, Object> args,
                              ToolDescriptor descriptor) {
        String toolId = descriptor.id().value();
        String contentType = bodySpec.contentType() == null || bodySpec.contentType().isBlank()
                ? MediaType.APPLICATION_JSON_VALUE : bodySpec.contentType().toLowerCase(Locale.ROOT);
        if (contentType.contains("json")) {
            return renderJsonBody(bodySpec.template(), args, descriptor);
        }
        if (contentType.contains("x-www-form-urlencoded")) {
            return render(bodySpec.template(), args, toolId, true);
        }
        return renderTemplate(bodySpec.template(), args, toolId);
    }

    /**
     * Renders a JSON body so a substituted value is always exactly one JSON
     * value and can never change the shape the template declares — the request
     * the system of record receives has the fields the user was shown.
     *
     * <ul>
     *   <li>Inside a string literal ({@code "reason":"{reason}"}) the value is
     *       JSON-string-escaped.</li>
     *   <li>Unquoted, standing as a whole value ({@code "amount":{amount}},
     *       {@code "lines":{linesJson}}) it must be one well-formed JSON value
     *       of the type the tool's input schema declares; see
     *       {@link #rawJsonValue}.</li>
     *   <li>Unquoted anywhere else (part of a token, a key) only letters,
     *       digits and {@code _ . + -} are accepted.</li>
     * </ul>
     */
    private String renderJsonBody(String template, Map<String, Object> args, ToolDescriptor descriptor) {
        String toolId = descriptor.id().value();
        JsonNode[] schemaProps = new JsonNode[1];
        StringBuilder out = new StringBuilder(template.length() + 64);
        Matcher m = PLACEHOLDER.matcher(template);
        boolean inString = false;
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (inString && c == '\\' && i + 1 < template.length()) {
                out.append(c).append(template.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '"') {
                inString = !inString;
            } else if (c == '{' && m.region(i, template.length()).lookingAt()) {
                String path = m.group(1);
                Object value = resolve(path, args, toolId);
                if (inString) {
                    out.append(escapeJsonString(value == null ? "" : value.toString()));
                } else if (standsAsWholeValue(template, i, m.end())) {
                    if (schemaProps[0] == null) schemaProps[0] = inputProperties(descriptor);
                    out.append(rawJsonValue(path, value, schemaProps[0].path(path), toolId));
                } else {
                    String token = value == null ? "" : value.toString();
                    if (!SAFE_TOKEN.matcher(token).matches()) {
                        throw new TemplateRenderException("HTTP tool '" + toolId + "' value for {" + path
                                + "} may only contain letters, digits and _ . + - — nothing was sent.");
                    }
                    out.append(token);
                }
                i = m.end();
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Za-z0-9_.+-]*");

    /** Strict: no comments, no single quotes, no duplicate keys, nothing after the value. */
    private static final ObjectReader STRICT_JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build()
            .readerFor(JsonNode.class)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private static boolean standsAsWholeValue(String template, int start, int end) {
        int before = start - 1;
        while (before >= 0 && Character.isWhitespace(template.charAt(before))) before--;
        int after = end;
        while (after < template.length() && Character.isWhitespace(template.charAt(after))) after++;
        boolean opens = before < 0 || ":[,".indexOf(template.charAt(before)) >= 0;
        boolean closes = after >= template.length() || ",}]".indexOf(template.charAt(after)) >= 0;
        return opens && closes;
    }

    /**
     * An unquoted value. Typed arguments (number, boolean, object, array) are
     * written as JSON. A string is taken as JSON text the caller serialised
     * already — the {@code linesJson} convention — and must parse as exactly
     * one value of an accepted type:
     * <ul>
     *   <li>schema type {@code string}: an object or an array, or the types
     *       named by the property's {@code contentSchema.type} when present;</li>
     *   <li>schema type {@code number}/{@code integer}/{@code boolean}/
     *       {@code object}/{@code array}: that type;</li>
     *   <li>not declared (a context variable): any single value.</li>
     * </ul>
     * An absent value is {@code null}. The text is sent as given.
     */
    private String rawJsonValue(String path, Object value, JsonNode property, String toolId) {
        if (value == null || (value instanceof CharSequence cs && cs.toString().isBlank())) {
            return "null";
        }
        if (value instanceof Boolean) return value.toString();
        if (value instanceof Number n) {
            if ((n instanceof Double d && !Double.isFinite(d)) || (n instanceof Float f && !Float.isFinite(f))) {
                throw new TemplateRenderException("HTTP tool '" + toolId + "' argument {" + path
                        + "} is not a finite number — nothing was sent.");
            }
            return n.toString();
        }
        if (value instanceof Map<?, ?> || value instanceof Collection<?> || value.getClass().isArray()) {
            try {
                return json.writeValueAsString(value);
            } catch (Exception e) {
                throw new TemplateRenderException("HTTP tool '" + toolId + "' argument {" + path
                        + "} could not be written as JSON — nothing was sent.");
            }
        }
        String text = value.toString();
        Set<String> accepted = acceptedTypes(property);
        JsonNode parsed;
        try {
            parsed = STRICT_JSON.readTree(text);
        } catch (Exception e) {
            parsed = null;
        }
        if (parsed == null || parsed.isMissingNode()) {
            throw new TemplateRenderException("HTTP tool '" + toolId + "' argument {" + path
                    + "} must be " + describe(accepted) + " and it is not well-formed JSON — nothing was sent.");
        }
        String actual = switch (parsed.getNodeType()) {
            case OBJECT -> "object";
            case ARRAY -> "array";
            case NUMBER -> "number";
            case BOOLEAN -> "boolean";
            case STRING -> "string";
            case NULL -> "null";
            default -> "other";
        };
        if (!accepted.isEmpty() && !accepted.contains(actual)) {
            throw new TemplateRenderException("HTTP tool '" + toolId + "' argument {" + path
                    + "} must be " + describe(accepted) + " but a JSON " + actual
                    + " was given — nothing was sent.");
        }
        return text;
    }

    /** Empty = no declaration, anything goes. */
    private static Set<String> acceptedTypes(JsonNode property) {
        Set<String> declared = typesOf(property.path("type"));
        if (declared.isEmpty()) return declared;
        Set<String> accepted = new LinkedHashSet<>();
        for (String t : declared) {
            if (t.equals("string")) {
                Set<String> content = typesOf(property.path("contentSchema").path("type"));
                accepted.addAll(content.isEmpty() ? List.of("object", "array") : content);
            } else {
                accepted.add(t.equals("integer") ? "number" : t);
            }
        }
        return accepted;
    }

    private static Set<String> typesOf(JsonNode type) {
        Set<String> out = new LinkedHashSet<>();
        if (type.isTextual()) {
            out.add(type.asText());
        } else if (type.isArray()) {
            type.forEach(t -> { if (t.isTextual()) out.add(t.asText()); });
        }
        if (out.remove("integer")) out.add("number");
        return out;
    }

    private static String describe(Set<String> accepted) {
        return accepted.isEmpty() ? "a JSON value" : "a JSON " + String.join(" or ", accepted);
    }

    private JsonNode inputProperties(ToolDescriptor descriptor) {
        try {
            String schema = descriptor.inputSchemaJson();
            if (schema != null && !schema.isBlank()) return json.readTree(schema).path("properties");
        } catch (Exception ignored) {
            // An unreadable schema declares nothing.
        }
        return MissingNode.getInstance();
    }

    private static String escapeJsonString(String raw) {
        return new String(JsonStringEncoder.getInstance().quoteAsString(raw));
    }

    private static boolean hasControlChars(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c < 0x20 && c != '\t') || c == 0x7f) return true;
        }
        return false;
    }

    private static final String PRINCIPAL_TOKEN_PLACEHOLDER = "{principalToken}";

    private static boolean referencesPrincipalToken(String headerTemplate) {
        return headerTemplate != null && headerTemplate.contains(PRINCIPAL_TOKEN_PLACEHOLDER);
    }

    /** Names the platform fills in. A model-supplied argument never stands in for one. */
    private static final Set<String> RESERVED_NAMES =
            Set.of("principal", "call", "session", "turn", "principalToken", "secret");

    /** Template root of the user-typed secret: {@code {secret.value}}. */
    private static final String SECRET_NAME = "secret";

    /**
     * {@link ExecutionContext#attributes()} key under which the engine hands
     * over the secret collected by a masked prompt, for the one call that
     * asked for it. Never logged, never part of a result or its diagnostics.
     */
    public static final String SECRET_ATTRIBUTE = "secretValue";

    private static boolean referencesSecret(String headerTemplate) {
        return headerTemplate != null && headerTemplate.contains("{" + SECRET_NAME + ".");
    }

    private static Map<String, Object> withSecret(Map<String, Object> richArgs, ExecutionContext ctx) {
        Object secret = ctx == null || ctx.attributes() == null ? null : ctx.attributes().get(SECRET_ATTRIBUTE);
        if (secret == null || secret.toString().isEmpty()) return richArgs;
        Map<String, Object> with = new LinkedHashMap<>(richArgs);
        with.put(SECRET_NAME, Map.of("value", secret.toString()));
        return with;
    }

    /**
     * Merge LLM-supplied {@code args} with verified server-side context so
     * templates can reference {@code {principal.*}}, {@code {call.id}},
     * {@code {session.id}}, {@code {session.channel}} and {@code {turn.id}}
     * without having to trust the model to pass them as arguments.
     */
    private static Map<String, Object> enrichArgs(Map<String, Object> args,
                                                  ExecutionContext ctx,
                                                  String callId) {
        Map<String, Object> rich = new LinkedHashMap<>(args == null ? Map.of() : args);
        rich.keySet().removeAll(RESERVED_NAMES);
        if (ctx != null && ctx.principal() != null) {
            Map<String, Object> principalNode = new LinkedHashMap<>();
            principalNode.put("subject", ctx.principal().subject());
            if (ctx.principal().tenant() != null) {
                principalNode.put("tenant", ctx.principal().tenant().value());
            }
            principalNode.put("claims", normalizeOrgClaim(ctx.principal().claims()));
            rich.put("principal", principalNode);
        }
        rich.put("call", Map.of("id", callId == null ? "" : callId));
        if (ctx != null) {
            Map<String, Object> sessionNode = new LinkedHashMap<>();
            if (ctx.sessionId() != null) sessionNode.put("id", ctx.sessionId().value());
            Object channel = ctx.attributes() == null ? null : ctx.attributes().get(TurnChannel.ATTRIBUTE);
            if (channel != null) sessionNode.put("channel", TurnChannel.of(channel.toString(), null));
            if (!sessionNode.isEmpty()) rich.put("session", sessionNode);
            if (ctx.turnId() != null) {
                rich.put("turn", Map.of("id", ctx.turnId().value()));
            }
        }
        return rich;
    }

    /**
     * Tool URL templates scope every {@code /api/ai/...} call with
     * {@code ?organizationId={principal.claims.org}}. Auth resolvers, however,
     * mint the org under whatever key their identity service uses — commonly
     * the backend's own {@code organizationId} param name. Canonicalise those
     * aliases onto {@code org} so a resolver JWT carrying {@code organizationId}
     * (or {@code organization_id}/{@code org_id}) still populates the template
     * instead of silently rendering blank. The original claim keys are left
     * intact; we only add {@code org} when it is missing.
     */
    private static final List<String> ORG_CLAIM_ALIASES =
            List.of("organizationId", "organization_id", "org_id", "orgId");

    private static Map<String, Object> normalizeOrgClaim(Map<String, Object> claims) {
        if (claims == null) return Map.of();
        Object canonical = claims.get("org");
        if (canonical != null && !canonical.toString().isBlank()) return claims;
        for (String alias : ORG_CLAIM_ALIASES) {
            Object v = claims.get(alias);
            if (v != null && !v.toString().isBlank()) {
                Map<String, Object> copy = new LinkedHashMap<>(claims);
                copy.put("org", v);
                return copy;
            }
        }
        return claims;
    }

    /** Signals a blank {@code principal.*} template value — surfaced as a
     *  FAILED tool result rather than a silent empty substitution. */
    private static final class MissingContextException extends TemplateRenderException {
        MissingContextException(String message) { super(message); }
    }

    /** A template value that cannot be sent as it is — surfaced as a FAILED
     *  tool result before any request leaves. */
    private static class TemplateRenderException extends RuntimeException {
        TemplateRenderException(String message) { super(message); }
    }

    @SuppressWarnings("unchecked")
    private static Object pluck(Object source, String path) {
        if (source == null) return null;
        Object cursor = source;
        for (String segment : path.split("\\.")) {
            if (cursor instanceof Map<?, ?> map) {
                cursor = ((Map<String, Object>) map).get(segment);
            } else {
                return null;
            }
            if (cursor == null) return null;
        }
        return cursor;
    }

    private Turn.ToolResult project(String callId,
                                    HttpToolSpec.ResponseProjection projection,
                                    String responseBody) {
        if (projection == null || projection.jsonPath() == null || projection.jsonPath().isBlank()
                || "$".equals(projection.jsonPath())) {
            return parseFreely(callId, responseBody);
        }
        try {
            Object extracted = JsonPath.read(responseBody, projection.jsonPath());
            return new Turn.ToolResult(callId, Turn.ToolResult.Status.OK, extracted, null);
        } catch (PathNotFoundException e) {
            return failed(callId, "response projection: path not found " + projection.jsonPath());
        } catch (Exception e) {
            return failed(callId, "response projection failed: " + e.getMessage());
        }
    }

    private Turn.ToolResult parseFreely(String callId, String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return new Turn.ToolResult(callId, Turn.ToolResult.Status.OK, null, null);
        }
        try {
            Object parsed = json.readValue(responseBody, Object.class);
            return new Turn.ToolResult(callId, Turn.ToolResult.Status.OK, parsed, null);
        } catch (Exception e) {
            return new Turn.ToolResult(callId, Turn.ToolResult.Status.OK, responseBody, null);
        }
    }

    private void applyAuthForwarding(HttpToolSpec.AuthForwarding mode,
                                     ExecutionContext ctx,
                                     Map<String, String> headers) {
        if (mode == null || mode == HttpToolSpec.AuthForwarding.NONE) return;
        switch (mode) {
            case PRINCIPAL_TOKEN -> {
                // A token re-minted earlier this turn (reactive 409 or the
                // registration fast-path) wins over the inbound one, so the
                // upgraded PLAYER_QUERY_OWN scope reaches every subsequent call.
                String token = tokenRefresher == null
                        ? null : tokenRefresher.current(ctx).orElse(null);
                if (token == null) {
                    token = ctx == null || ctx.attributes() == null
                            ? null : (String) ctx.attributes().get("inboundAuthorization");
                }
                if (token != null && !token.isBlank()) {
                    headers.put(HttpHeaders.AUTHORIZATION, token);
                }
            }
            case SERVICE_TOKEN -> {
                String tenantId = ctx == null || ctx.principal() == null || ctx.principal().tenant() == null
                        ? null : ctx.principal().tenant().value();
                byte[] secret = tenantId == null ? null : serviceTokenSecrets.get(tenantId);
                if (secret == null) return;
                String subject = ctx.principal().subject();
                String scopes = String.join(",", ctx.principal().scopes());
                long exp = Instant.now().getEpochSecond() + 60;
                String payload = tenantId + "." + subject + "." + exp + "." + scopes;
                String token = payload + "." + hmacB64Url(secret, payload);
                headers.put("X-Yaya-Service-Token", token);
            }
            default -> { /* exhaustive: NONE handled above */ }
        }
    }

    private static String hmacB64Url(byte[] key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] sig = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private static Turn.ToolResult failed(String callId, String reason) {
        return new Turn.ToolResult(callId, Turn.ToolResult.Status.FAILED, null, reason);
    }
}
