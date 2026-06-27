package com.yayatechandinnovations.yayaagentic.operator_auth.web;

import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import com.yayatechandinnovations.yayaagentic.operator_auth.Operator;
import com.yayatechandinnovations.yayaagentic.operator_auth.OperatorAuthenticationException;
import com.yayatechandinnovations.yayaagentic.operator_auth.OperatorAuthenticator;
import com.yayatechandinnovations.yayaagentic.operator_auth.OperatorCredentials;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Membership;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.OperatorAccountService;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.UserId;
import com.yayatechandinnovations.yayaagentic.operator_auth.audit.OperatorLoginAudit;
import com.yayatechandinnovations.yayaagentic.operator_auth.ratelimit.LoginRateLimiter;
import com.yayatechandinnovations.yayaagentic.operator_auth.ratelimit.RateLimitedException;
import com.yayatechandinnovations.yayaagentic.operator_auth.session.OperatorSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.yayatechandinnovations.yayaagentic.operator_auth.apikeys.RejectForApiKeys;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Console-operator authentication endpoints. The
 * {@link OperatorAuthWebFilter} protects {@code /v1/auth/me} and
 * {@code /v1/auth/logout}; {@code /v1/auth/login} is intentionally open
 * (it's what creates a session).
 *
 * <p>On every failed login the response body is the same generic message —
 * which strategy denied and why is audit-only (logged at INFO; Phase 3
 * persists to {@code audit_operator_login}).</p>
 */
@RestController
@RequestMapping("/v1/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private static final String GENERIC_DENIAL = "Invalid username or password";

    private final OperatorAuthenticator chain;
    private final OperatorSessionService sessions;
    private final OperatorLoginAudit audit;
    private final LoginRateLimiter rateLimiter;
    private final OperatorAccountService accounts;
    private final String cookieName;
    private final Duration ttl;
    private final boolean cookieSecure;

    public AuthController(OperatorAuthenticator chain,
                          OperatorSessionService sessions,
                          OperatorLoginAudit audit,
                          LoginRateLimiter rateLimiter,
                          OperatorAccountService accounts,
                          YayaAgenticProperties props) {
        this.chain = chain;
        this.sessions = sessions;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
        this.accounts = accounts;
        var sess = props.operatorAuth() == null ? null : props.operatorAuth().session();
        this.cookieName = sess == null || isBlank(sess.cookieName()) ? "YAYA_SESSION" : sess.cookieName();
        this.ttl = sess == null || sess.ttl() == null ? Duration.ofHours(8) : sess.ttl();
        this.cookieSecure = sess != null && sess.cookieSecure();
    }

    @PostMapping("/login")
    public Mono<ResponseEntity<MeResponse>> login(@RequestBody LoginRequest body, ServerWebExchange exchange) {
        if (body == null || isBlank(body.username()) || isBlank(body.password())) {
            return Mono.error(unauthorized("missing credentials"));
        }
        String ip = clientIp(exchange);
        String ua = userAgent(exchange);
        String attemptId = UUID.randomUUID().toString();
        long start = System.nanoTime();

        // Rate-limit check fires BEFORE the chain runs so we don't burn an
        // argon2 / HTTP-delegate roundtrip on a clearly spammy attempt.
        var rl = rateLimiter.check(body.username(), ip);
        if (rl instanceof LoginRateLimiter.Outcome.Denied d) {
            auditDeny(body.username(), null, "rate_limited:" + d.axis(), ip, ua, attemptId, start);
            log.info("operator-login deny: rate_limited axis={} retryAfter={}s (user={}, ip={}, attempt={})",
                    d.axis(), d.retryAfterSeconds(), body.username(), ip, attemptId);
            return Mono.error(new RateLimitedException(d.axis(), d.retryAfterSeconds()));
        }

        OperatorCredentials creds = new OperatorCredentials(
                body.username(), body.password().toCharArray(), ip, ua, attemptId);

        return Mono.fromCallable(() -> chain.tryAuthenticate(creds))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(maybe -> maybe
                        .map(op -> {
                            // Session-fixation guard: revoke any pre-existing
                            // session bound to the inbound cookie before
                            // minting a new one. Prevents an attacker who set
                            // the cookie value from later inheriting an
                            // authenticated session.
                            revokeInboundSession(exchange);
                            auditAllow(op, body.username(), ip, ua, attemptId, start);
                            return issueSession(op, ip, ua, exchange, attemptId);
                        })
                        .orElseGet(() -> {
                            auditDeny(body.username(), null, "no_strategy_claimed", ip, ua, attemptId, start);
                            log.info("operator-login deny: no_strategy_claimed (user={}, attempt={})",
                                    body.username(), attemptId);
                            return Mono.error(unauthorized("no_strategy_claimed"));
                        }))
                .onErrorResume(OperatorAuthenticationException.class, ex -> {
                    auditDeny(body.username(), translateSource(ex.source()),
                            ex.getMessage(), ip, ua, attemptId, start);
                    log.info("operator-login deny: {} (user={}, source={}, attempt={})",
                            ex.getMessage(), body.username(), ex.source(), attemptId);
                    return Mono.error(unauthorized(ex.getMessage()));
                });
    }

    private void revokeInboundSession(ServerWebExchange exchange) {
        var cookie = exchange.getRequest().getCookies().getFirst(cookieName);
        if (cookie != null && !cookie.getValue().isBlank()) {
            sessions.revoke(cookie.getValue());
        }
    }

    private void auditAllow(Operator op, String username, String ip, String ua,
                            String attemptId, long startNanos) {
        long ms = (System.nanoTime() - startNanos) / 1_000_000;
        audit.record(new OperatorLoginAudit.Record(
                username, OperatorLoginAudit.Decision.ALLOW,
                op.source().name(), null, ip, ua, attemptId, ms));
    }

    private void auditDeny(String username, String source, String auditReason,
                           String ip, String ua, String attemptId, long startNanos) {
        long ms = (System.nanoTime() - startNanos) / 1_000_000;
        audit.record(new OperatorLoginAudit.Record(
                username, OperatorLoginAudit.Decision.DENY,
                source, auditReason, ip, ua, attemptId, ms));
    }

    /** "bootstrap" → "BOOTSTRAP"; "http-delegate" → "HTTP_DELEGATE"; null → null. */
    private static String translateSource(String name) {
        if (name == null) return null;
        return name.toUpperCase().replace('-', '_');
    }

    @PostMapping("/logout")
    @RejectForApiKeys(status = HttpStatus.BAD_REQUEST, reason = "api_key_session_endpoint")
    public Mono<ResponseEntity<Void>> logout(ServerWebExchange exchange) {
        var cookie = exchange.getRequest().getCookies().getFirst(cookieName);
        String token = cookie == null ? null : cookie.getValue();
        return Mono.<Void>fromRunnable(() -> sessions.revoke(token))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.fromSupplier(() -> ResponseEntity.noContent()
                        .header("Set-Cookie", clearCookie().toString())
                        .<Void>build()));
    }

    @GetMapping("/me")
    @RejectForApiKeys(status = HttpStatus.BAD_REQUEST, reason = "api_key_session_endpoint")
    public Mono<ResponseEntity<MeResponse>> me(ServerWebExchange exchange) {
        return Mono.justOrEmpty(OperatorContext.current(exchange))
                .flatMap(op -> Mono.fromCallable(() -> ResponseEntity.ok(meResponseFor(op)))
                        .subscribeOn(Schedulers.boundedElastic()))
                .switchIfEmpty(Mono.error(unauthorized("no_operator_on_exchange")));
    }

    /**
     * Build a {@link MeResponse} that includes the user's workspaces, when
     * we can identify the user. Sessions sourced from {@code BOOTSTRAP}
     * don't have a user UUID and surface with empty memberships — the
     * platform-admin flag is still set so the UI can show the cross-tenant
     * tools.
     */
    private MeResponse meResponseFor(Operator op) {
        List<WorkspaceMembershipDto> ws = List.of();
        boolean platformAdmin = false;
        Object adminAttr = op.attributes() == null ? null : op.attributes().get("is_platform_admin");
        if (adminAttr instanceof Boolean b) platformAdmin = b;

        Object userIdAttr = op.attributes() == null ? null : op.attributes().get("user_id");
        if (userIdAttr instanceof String s && !s.isBlank()) {
            try {
                UserId uid = UserId.parse(s);
                List<Membership> mine = accounts.workspacesFor(uid);
                List<WorkspaceMembershipDto> built = new ArrayList<>(mine.size());
                for (Membership m : mine) {
                    built.add(new WorkspaceMembershipDto(m.workspaceId().value(), m.role().name()));
                }
                ws = built;
                // Re-derive platform-admin from the persisted user row so a
                // stale session attribute can't keep someone admin after a
                // demotion.
                platformAdmin = accounts.findById(uid).map(u -> u.isPlatformAdmin()).orElse(platformAdmin);
            } catch (IllegalArgumentException ignored) {
                // user_id attribute wasn't a valid UUID — leave defaults.
            }
        }
        return MeResponse.of(op, ws, platformAdmin);
    }

    // ------------------------------------------------------------------

    private Mono<ResponseEntity<MeResponse>> issueSession(Operator op, String ip, String ua,
                                                          ServerWebExchange exchange, String attemptId) {
        return Mono.fromCallable(() -> {
                    OperatorSessionService.Issued issued = sessions.create(op, ip, ua);
                    MeResponse body = meResponseFor(op);
                    return ResponseEntity.ok()
                            .header("Set-Cookie", sessionCookie(issued.rawToken()).toString())
                            .body(body);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(r -> log.info("operator-login allow: subject={} source={} attempt={}",
                        op.subject(), op.source(), attemptId));
    }

    private ResponseCookie sessionCookie(String token) {
        return ResponseCookie.from(cookieName, token)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(ttl)
                .build();
    }

    private ResponseCookie clearCookie() {
        return ResponseCookie.from(cookieName, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
    }

    private ResponseStatusException unauthorized(String auditReason) {
        // auditReason is for logs; the body the user sees is GENERIC_DENIAL.
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, GENERIC_DENIAL);
    }

    private static String clientIp(ServerWebExchange exchange) {
        var addr = exchange.getRequest().getRemoteAddress();
        return addr == null ? null : addr.getAddress().getHostAddress();
    }

    private static String userAgent(ServerWebExchange exchange) {
        return exchange.getRequest().getHeaders().getFirst("User-Agent");
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    // ---- DTOs ---------------------------------------------------------

    public record LoginRequest(String username, String password) {}

    public record MeResponse(
            String subject,
            String displayName,
            String source,
            Map<String, Object> attributes,
            Instant verifiedAt,
            List<WorkspaceMembershipDto> memberships,
            boolean platformAdmin
    ) {
        public static MeResponse of(Operator op, List<WorkspaceMembershipDto> memberships, boolean platformAdmin) {
            return new MeResponse(op.subject(), op.displayName(), op.source().name(),
                    op.attributes(), op.verifiedAt(), memberships, platformAdmin);
        }
    }

    public record WorkspaceMembershipDto(String workspaceId, String role) {}
}
