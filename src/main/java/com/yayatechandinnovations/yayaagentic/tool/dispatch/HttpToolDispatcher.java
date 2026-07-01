package com.yayatechandinnovations.yayaagentic.tool.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.core.Turn;
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

    private final WebClient.Builder webClientBuilder;
    private final HttpEgressPolicy egress;
    private final BaseUrlResolver baseUrlResolver;
    private final ObjectMapper json;
    private final Duration defaultTimeout;
    private final Map<String, byte[]> serviceTokenSecrets;

    public HttpToolDispatcher(WebClient.Builder webClientBuilder,
                              HttpEgressPolicy egress,
                              BaseUrlResolver baseUrlResolver,
                              ObjectMapper json,
                              YayaAgenticProperties props) {
        this.webClientBuilder = webClientBuilder;
        this.egress = egress;
        this.baseUrlResolver = baseUrlResolver;
        this.json = json;
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
        } catch (MissingContextException e) {
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
        try {
            if (spec.headerTemplates() != null) {
                spec.headerTemplates().forEach((k, v) -> headers.put(k, renderTemplate(v, richArgs, toolId)));
            }
            if (spec.body() != null && spec.body().template() != null
                    && spec.method() != HttpToolSpec.HttpMethod.GET
                    && spec.method() != HttpToolSpec.HttpMethod.DELETE) {
                body = renderTemplate(spec.body().template(), richArgs, toolId);
            }
        } catch (MissingContextException e) {
            return failed(callId, e.getMessage());
        }
        applyAuthForwarding(spec.authForwarding(), ctx, headers);
        // Diagnostics for the playground auth-flow inspector: which forwarding
        // mode this tool uses and whether an auth header actually got attached.
        // A PRINCIPAL_TOKEN tool with authHeaderPresent=false is the smoking gun
        // for a 401 (no token was forwarded). See
        // playground-auth-flow-inspector-design.md §6.3.
        Map<String, Object> diag = authDiagnostics(spec.authForwarding(), headers);

        WebClient client = webClientBuilder.build();
        ToolPolicy policy = descriptor.policy() == null ? ToolPolicy.defaults() : descriptor.policy();
        Duration timeout = policy.timeout() == null ? defaultTimeout : policy.timeout();

        try {
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
            if (policy.maxRetries() > 0) {
                // Only retry transient failures. Retrying a 404 / 401 / 422
                // just wastes time and floods the logs with "Retries
                // exhausted" wrappers that hide the real cause.
                mono = mono.retryWhen(Retry.backoff(policy.maxRetries(), Duration.ofMillis(200))
                        .filter(HttpToolDispatcher::isTransient));
            }
            String responseBody = mono.block();
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
     * interpolated values are encoded.
     */
    private String renderUrlTemplate(String template, Map<String, Object> args, String toolId) {
        return render(template, args, toolId, true);
    }

    private String render(String template, Map<String, Object> args, String toolId, boolean urlEncodeValues) {
        if (template == null) return null;
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String path = m.group(1);
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
            if (blank) {
                // Optional LLM-supplied arg absent — keep the historical
                // empty substitution, but make the miss visible in logs.
                LOG.warn("template placeholder {{{}}} resolved to blank in toolId={} — substituting empty string",
                        path, toolId);
            }
            String rendered = value == null ? "" : value.toString();
            if (urlEncodeValues && !rendered.isEmpty()) {
                rendered = UriUtils.encode(rendered, StandardCharsets.UTF_8);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(rendered));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Merge LLM-supplied {@code args} with verified server-side context so
     * templates can reference {@code {principal.*}} and {@code {call.id}}
     * without having to trust the model to pass them as arguments.
     */
    private static Map<String, Object> enrichArgs(Map<String, Object> args,
                                                  ExecutionContext ctx,
                                                  String callId) {
        Map<String, Object> rich = new LinkedHashMap<>(args == null ? Map.of() : args);
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
            if (ctx.sessionId() != null) {
                rich.put("session", Map.of("id", ctx.sessionId().value()));
            }
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
    private static final class MissingContextException extends RuntimeException {
        MissingContextException(String message) { super(message); }
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
                String token = ctx == null || ctx.attributes() == null
                        ? null : (String) ctx.attributes().get("inboundAuthorization");
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
