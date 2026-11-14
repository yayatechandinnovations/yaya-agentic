package com.yayatechandinnovations.yayaagentic.operator_auth.apikeys;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;
import com.yayatechandinnovations.yayaagentic.operator_auth.Operator;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.web.OperatorContext;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceContext;
import com.yayatechandinnovations.yayaagentic.persistence.ApiKeyEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ApiKeyRepository;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Authenticates {@code Authorization: Bearer yk_live_*} on the operator plane.
 * Runs before {@code CsrfWebFilter} and {@code OperatorAuthWebFilter} so a
 * bearer request is treated as a key request and the cookie is ignored (§5).
 *
 * <p>On success it stamps the {@link Operator} (source {@code API_KEY}) and the
 * pinned workspace + role onto the exchange, then continues. On any failure it
 * 401/400/429s with a {@code deny_reason} attribute for audit and NEVER logs
 * the bearer. The DB lookup runs on {@code boundedElastic}.</p>
 *
 * <p>This filter only engages on operator-plane paths; a {@code yk_} bearer sent
 * to {@code /v1/sessions/**} falls through untouched — API keys do not grant the
 * conversational plane (§2). The one exception is the read-only session
 * inspector, an operator view that happens to live under that prefix.</p>
 */
@Component
public class ApiKeyAuthFilter implements WebFilter, Ordered {

    private static final PathPatternParser PARSER = PathPatternParser.defaultInstance;
    private static final List<PathPattern> PROTECTED = List.of(
            PARSER.parse("/v1/admin/**"),
            PARSER.parse("/v1/auth/me"),
            PARSER.parse("/v1/auth/logout"),
            PARSER.parse("/v1/sessions/{id}/inspector"));

    private static final String BEARER = "Bearer ";
    private static final String YK = "yk_";

    private final ApiKeyRepository repo;
    private final ApiKeyCache cache;
    private final SecretGenerator secrets;
    private final ApiKeyAuditWriter audit;
    private final ApiKeyBackgroundExecutor background;
    private final FailedBearerRateLimiter rateLimiter;
    private final ObjectMapper json;
    private final boolean requireSecureTransport;

    public ApiKeyAuthFilter(ApiKeyRepository repo, ApiKeyCache cache, SecretGenerator secrets,
                            ApiKeyAuditWriter audit, ApiKeyBackgroundExecutor background,
                            FailedBearerRateLimiter rateLimiter, ObjectMapper json,
                            ApiKeysProperties props, Environment env) {
        this.repo = repo;
        this.cache = cache;
        this.secrets = secrets;
        this.audit = audit;
        this.background = background;
        this.rateLimiter = rateLimiter;
        this.json = json;
        boolean propRequire = props.requireSecureTransportOrDefault();
        // Plaintext bearer is rejected outside dev — same rule as the HTTP
        // delegate (§5.3). prod always requires TLS; dev never does.
        boolean prod = env.acceptsProfiles(org.springframework.core.env.Profiles.of("prod"));
        boolean dev = env.acceptsProfiles(org.springframework.core.env.Profiles.of("dev"));
        this.requireSecureTransport = (propRequire || prod) && !dev;
    }

    @Override
    public int getOrder() {
        // Before CsrfWebFilter (+5) and OperatorAuthWebFilter (+10); after Cors.
        return Ordered.HIGHEST_PRECEDENCE + 2;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (!isProtected(request)) return chain.filter(exchange);

        // Token in a query string is always a hard error (§10): URLs leak into
        // access logs / browser history / referers. Reject regardless of header.
        if (request.getQueryParams().containsKey("api_key")) {
            return deny(exchange, HttpStatus.BAD_REQUEST, "api_key_in_query_string", null, null);
        }

        String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        String bearer = header != null && header.startsWith(BEARER)
                ? header.substring(BEARER.length()).trim() : null;
        if (bearer == null || !bearer.startsWith(YK)) {
            return chain.filter(exchange);   // not ours — let cookie auth run
        }

        String ip = clientIp(request);
        if (!rateLimiter.permitAttempt(ip)) {
            return tooManyRequests(exchange);
        }
        if (!bearer.startsWith(SecretGenerator.LIVE_PREFIX)) {
            return failed(exchange, "api_key_env_rejected", ip, null, null);
        }
        if (requireSecureTransport && !isSecure(request)) {
            return failed(exchange, "api_key_insecure_transport", ip, null, null);
        }
        if (!secrets.looksWellFormedLive(bearer)) {
            return failed(exchange, "api_key_malformed", ip, null, null);
        }

        final String token = bearer;
        return Mono.fromCallable(() -> verify(token))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(result -> switch (result) {
                    case Verdict.Ok ok -> {
                        bind(exchange, ok.key());
                        background.submit(() -> touch(ok.key().getId(), ip));
                        yield chain.filter(exchange);
                    }
                    case Verdict.Denied d -> failed(exchange, d.reason(), ip, d.workspaceId(), d.apiKeyId());
                });
    }

    // ------------------------------------------------------------------
    // Verification (blocking — runs on boundedElastic)
    // ------------------------------------------------------------------

    private sealed interface Verdict {
        record Ok(ApiKeyEntity key) implements Verdict {}
        record Denied(String reason, String workspaceId, java.util.UUID apiKeyId) implements Verdict {}
    }

    private Verdict verify(String token) {
        byte[] hash = secrets.sha256(token);
        String prefix = secrets.prefixOf(token);
        List<ApiKeyEntity> candidates = cache.activeByPrefix(prefix);
        ApiKeyEntity match = null;
        for (ApiKeyEntity e : candidates) {
            if (MessageDigest.isEqual(e.getSecretHash(), hash)) {
                match = e;
                break;
            }
        }
        if (match == null) {
            return new Verdict.Denied("api_key_unknown_or_revoked", null, null);
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (match.getExpiresAt() != null && !match.getExpiresAt().isAfter(now)) {
            return new Verdict.Denied("api_key_expired", match.getWorkspaceId(), match.getId());
        }
        return new Verdict.Ok(match);
    }

    private void bind(ServerWebExchange exchange, ApiKeyEntity key) {
        Operator op = new Operator(
                "apikey:" + key.getId(),
                key.getName() + " (key)",
                Operator.Source.API_KEY,
                apiKeyAttributes(key),
                OffsetDateTime.now(ZoneOffset.UTC).toInstant());
        exchange.getAttributes().put(OperatorContext.ATTR, op);
        exchange.getAttributes().put(WorkspaceContext.PINNED_ATTR, new TenantId(key.getWorkspaceId()));
        exchange.getAttributes().put(WorkspaceContext.PINNED_ROLE_ATTR, Role.valueOf(key.getRole()));
    }

    private static Map<String, Object> apiKeyAttributes(ApiKeyEntity key) {
        // NOTE: user_id is the creator's id (may be null if the creator was
        // deleted). The API_KEY branch in WorkspaceAccessHelper never routes
        // through OperatorIdentity/membership, so a null here cannot escalate
        // to the synthetic platform admin. is_platform_admin comes ONLY from
        // the key's own platform_scope column (mintable exclusively by
        // platform-admin users — one-click provisioning design, gap B); an
        // ordinary key remains explicitly non-platform-admin.
        return Map.of(
                "user_id", key.getCreatedByUserId() == null ? "" : key.getCreatedByUserId().toString(),
                "workspace_id", key.getWorkspaceId(),
                "role", key.getRole(),
                "api_key_id", key.getId().toString(),
                "is_platform_admin", key.isPlatformScope());
    }

    private void touch(java.util.UUID id, String ip) {
        try {
            repo.touchLastUsed(id, OffsetDateTime.now(ZoneOffset.UTC), ip);
        } catch (RuntimeException ignored) {
            // last_used is observability, not correctness — drop on failure.
        }
    }

    // ------------------------------------------------------------------
    // Responses
    // ------------------------------------------------------------------

    /** A denied attempt: drain the brute-force budget + write USE_DENIED audit, then 401. */
    private Mono<Void> failed(ServerWebExchange exchange, String reason, String ip,
                              String workspaceId, java.util.UUID apiKeyId) {
        rateLimiter.recordFailure(ip);
        audit.recordDeniedAsync(workspaceId, apiKeyId, ip,
                exchange.getRequest().getHeaders().getFirst(HttpHeaders.USER_AGENT),
                Map.of("reason", reason));
        return deny(exchange, HttpStatus.UNAUTHORIZED, reason, workspaceId, apiKeyId);
    }

    private Mono<Void> deny(ServerWebExchange exchange, HttpStatus status, String reason,
                            String workspaceId, java.util.UUID apiKeyId) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().add(HttpHeaders.CACHE_CONTROL, "no-store");
        exchange.getAttributes().put("yaya.apikey.deny_reason", reason);
        // Generic body — a wrong prefix is indistinguishable from a wrong
        // suffix (§10). Never echo the bearer.
        String code = status == HttpStatus.BAD_REQUEST ? "BAD_REQUEST" : "UNAUTHORIZED";
        return writeBody(response, new ApiError(code, "authentication required"));
    }

    private Mono<Void> tooManyRequests(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().add(HttpHeaders.RETRY_AFTER, "60");
        exchange.getAttributes().put("yaya.apikey.deny_reason", "api_key_rate_limited");
        return writeBody(response, new ApiError("TOO_MANY_REQUESTS", "rate limit exceeded"));
    }

    private Mono<Void> writeBody(ServerHttpResponse response, ApiError err) {
        String body;
        try {
            body = json.writeValueAsString(err);
        } catch (Exception e) {
            body = "{\"error\":\"" + err.error() + "\",\"message\":\"" + err.message() + "\"}";
        }
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    // ------------------------------------------------------------------

    private boolean isProtected(ServerHttpRequest request) {
        var path = request.getPath().pathWithinApplication();
        for (PathPattern p : PROTECTED) {
            if (p.matches(path)) return true;
        }
        return false;
    }

    private static boolean isSecure(ServerHttpRequest request) {
        if (request.getSslInfo() != null) return true;
        String proto = request.getHeaders().getFirst("X-Forwarded-Proto");
        if (proto != null && proto.toLowerCase().contains("https")) return true;
        return request.getURI().getScheme() != null
                && request.getURI().getScheme().equalsIgnoreCase("https");
    }

    private static String clientIp(ServerHttpRequest request) {
        String fwd = request.getHeaders().getFirst("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            int comma = fwd.indexOf(',');
            return (comma > 0 ? fwd.substring(0, comma) : fwd).trim();
        }
        var addr = request.getRemoteAddress();
        return addr == null || addr.getAddress() == null ? null : addr.getAddress().getHostAddress();
    }

    private record ApiError(String error, String message) {}
}
