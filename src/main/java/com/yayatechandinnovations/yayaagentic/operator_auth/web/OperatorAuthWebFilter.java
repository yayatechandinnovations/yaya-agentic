package com.yayatechandinnovations.yayaagentic.operator_auth.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import com.yayatechandinnovations.yayaagentic.operator_auth.session.OperatorSessionService;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
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
import java.util.List;

/**
 * WebFlux filter protecting the operator-plane endpoints. Reads the session
 * cookie, looks up the server-side session, stashes the {@code Operator} on
 * the exchange for downstream handlers, and 401s when no valid session is
 * present. Unprotected paths (login, end-user sessions, actuator) are passed
 * through unchanged.
 *
 * <p>See {@code docs/design/operator-auth-design.md} §7. The JPA lookup runs
 * on the {@code boundedElastic} scheduler so the reactive event loop isn't
 * blocked.</p>
 */
@Component
public class OperatorAuthWebFilter implements WebFilter, Ordered {

    private static final PathPatternParser PARSER = PathPatternParser.defaultInstance;
    private static final List<PathPattern> PROTECTED = List.of(
            PARSER.parse("/v1/admin/**"),
            PARSER.parse("/v1/auth/me"),
            PARSER.parse("/v1/auth/logout"));

    /**
     * Paths where the operator is OPTIONAL — bind it if a session cookie is
     * present, but don't 401 if it isn't. Used by token-bearer endpoints
     * (invitation accept) that behave differently for signed-in vs anon
     * recipients.
     */
    private static final List<PathPattern> SOFT_AUTH = List.of(
            PARSER.parse("/v1/auth/invitations/accept"));

    private final OperatorSessionService sessions;
    private final String cookieName;
    private final ObjectMapper json;

    public OperatorAuthWebFilter(OperatorSessionService sessions,
                                 ObjectMapper json,
                                 YayaAgenticProperties props) {
        this.sessions = sessions;
        this.json = json;
        var sess = props.operatorAuth() == null ? null : props.operatorAuth().session();
        this.cookieName = sess == null || sess.cookieName() == null || sess.cookieName().isBlank()
                ? "YAYA_SESSION" : sess.cookieName();
    }

    @Override
    public int getOrder() {
        // Run AFTER the CorsWebFilter (which sits at HIGHEST_PRECEDENCE) so
        // OPTIONS preflights don't get 401'd.
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        boolean protectedPath = isProtected(request);
        boolean softAuthPath = !protectedPath && isSoftAuth(request);
        if (!protectedPath && !softAuthPath) return chain.filter(exchange);
        if (request.getMethod() != null
                && "OPTIONS".equalsIgnoreCase(request.getMethod().name())) {
            return chain.filter(exchange);
        }

        // A higher-precedence filter (ApiKeyAuthFilter) may have already bound
        // an operator from a Bearer token. Bearer wins: don't read the cookie
        // or touch the server-side session row. (api-keys-design §5)
        if (OperatorContext.current(exchange).isPresent()) {
            return chain.filter(exchange);
        }

        var cookie = request.getCookies().getFirst(cookieName);
        if (cookie == null || cookie.getValue().isBlank()) {
            if (softAuthPath) return chain.filter(exchange);   // anon visitor, no session
            return unauthorized(exchange, "no_session_cookie");
        }
        String token = cookie.getValue();

        return Mono.fromCallable(() -> sessions.lookup(token))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(maybe -> maybe
                        .map(loaded -> {
                            exchange.getAttributes().put(OperatorContext.ATTR, loaded.operator());
                            return chain.filter(exchange);
                        })
                        .orElseGet(() -> softAuthPath
                                ? chain.filter(exchange)
                                : unauthorized(exchange, "session_expired_or_unknown")));
    }

    private boolean isProtected(ServerHttpRequest request) {
        return matchesAny(request, PROTECTED);
    }

    private boolean isSoftAuth(ServerHttpRequest request) {
        return matchesAny(request, SOFT_AUTH);
    }

    private static boolean matchesAny(ServerHttpRequest request, List<PathPattern> patterns) {
        var path = request.getPath().pathWithinApplication();
        for (PathPattern p : patterns) {
            if (p.matches(path)) return true;
        }
        return false;
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String auditReason) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        // User-facing body is generic; auditReason is for logs/audit (no PII).
        String body;
        try {
            body = json.writeValueAsString(new ApiError("UNAUTHORIZED", "authentication required"));
        } catch (Exception e) {
            body = "{\"error\":\"UNAUTHORIZED\",\"message\":\"authentication required\"}";
        }
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        exchange.getAttributes().put("yaya.auth.deny_reason", auditReason);
        return response.writeWith(Mono.just(buffer));
    }

    private record ApiError(String error, String message) {}
}
