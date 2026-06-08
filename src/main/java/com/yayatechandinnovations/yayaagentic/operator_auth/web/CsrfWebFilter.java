package com.yayatechandinnovations.yayaagentic.operator_auth.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * Synchronizer-token CSRF filter for the operator plane.
 *
 * <p>{@code XSRF-TOKEN} cookie (NOT HttpOnly so the SPA can read it) +
 * {@code X-XSRF-TOKEN} header echo on state-changing methods. A missing
 * or mismatched header on a write returns 403 before any handler runs.</p>
 *
 * <p>Order is {@code HIGHEST_PRECEDENCE + 5} — runs before
 * {@link OperatorAuthWebFilter} so the cookie still gets minted on 401
 * responses (which is how the SPA bootstraps a token on first load).</p>
 */
@Component
public class CsrfWebFilter implements WebFilter, Ordered {

    public static final String COOKIE_NAME = "XSRF-TOKEN";
    public static final String HEADER_NAME = "X-XSRF-TOKEN";

    private static final PathPatternParser PARSER = PathPatternParser.defaultInstance;
    private static final List<PathPattern> PROTECTED = List.of(
            PARSER.parse("/v1/admin/**"),
            PARSER.parse("/v1/auth/login"),
            PARSER.parse("/v1/auth/logout"),
            PARSER.parse("/v1/auth/me"));
    private static final Set<String> STATE_CHANGING = Set.of("POST", "PUT", "PATCH", "DELETE");

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENC = Base64.getUrlEncoder().withoutPadding();

    private final ObjectMapper json;
    private final boolean cookieSecure;

    public CsrfWebFilter(ObjectMapper json, YayaAgenticProperties props) {
        this.json = json;
        var sess = props.operatorAuth() == null ? null : props.operatorAuth().session();
        this.cookieSecure = sess != null && sess.cookieSecure();
    }

    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 5; }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (!isProtected(request)) return chain.filter(exchange);

        var cookie = request.getCookies().getFirst(COOKIE_NAME);
        String token = cookie == null ? null : cookie.getValue();

        String method = request.getMethod() == null ? "" : request.getMethod().name();
        if (STATE_CHANGING.contains(method)) {
            String header = request.getHeaders().getFirst(HEADER_NAME);
            if (token == null || header == null || !constantTimeEquals(token, header)) {
                // For mutating requests we always reject on missing/mismatched
                // token — even when we'd otherwise mint a fresh one. The
                // client must do a GET first to bootstrap.
                return forbidden(exchange, "csrf_token_missing_or_mismatched");
            }
        }

        if (token == null) {
            String fresh = generateToken();
            exchange.getResponse().addCookie(buildCookie(fresh));
        }

        return chain.filter(exchange);
    }

    private boolean isProtected(ServerHttpRequest request) {
        var path = request.getPath().pathWithinApplication();
        for (PathPattern p : PROTECTED) {
            if (p.matches(path)) return true;
        }
        return false;
    }

    private ResponseCookie buildCookie(String value) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(false)        // intentional — the SPA needs to read it
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
                .build();
    }

    private static String generateToken() {
        byte[] buf = new byte[32];
        RANDOM.nextBytes(buf);
        return URL_ENC.encodeToString(buf);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }

    private Mono<Void> forbidden(ServerWebExchange exchange, String auditReason) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().add(HttpHeaders.CACHE_CONTROL, "no-store");
        String body;
        try {
            body = json.writeValueAsString(new ApiError("FORBIDDEN", "csrf token required"));
        } catch (Exception e) {
            body = "{\"error\":\"FORBIDDEN\",\"message\":\"csrf token required\"}";
        }
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        exchange.getAttributes().put("yaya.csrf.deny_reason", auditReason);
        return response.writeWith(Mono.just(buffer));
    }

    private record ApiError(String error, String message) {}
}
