package com.yayatechandinnovations.yayaagentic.operator_auth.web;

import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.OperatorAccountService;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.OperatorAccountService.RequestMeta;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.OperatorAccountService.SignupOutcome;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.OperatorAccountService.VerifyOutcome;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.SignupRequest;
import com.yayatechandinnovations.yayaagentic.operator_auth.ratelimit.RateLimitedException;
import com.yayatechandinnovations.yayaagentic.operator_auth.ratelimit.VerificationResendRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

/**
 * Self-serve account lifecycle endpoints. Gated by
 * {@code yaya.agentic.user-accounts.enabled} so the routes 404 when the
 * feature is off — matching the rollout matrix in PRD §8.2.
 *
 * <p>Sibling of {@link AuthController} (login/logout/me); the two
 * controllers share {@link AuthExceptionHandler} and the {@link
 * com.yayatechandinnovations.yayaagentic.operator_auth.ratelimit.LoginRateLimiter}
 * surface so error shapes stay consistent across {@code /v1/auth/**}.</p>
 */
@RestController
@RequestMapping("/v1/auth")
@ConditionalOnProperty(name = "yaya.agentic.user-accounts.enabled", havingValue = "true")
public class UserAccountsController {

    private static final Logger log = LoggerFactory.getLogger(UserAccountsController.class);

    private final OperatorAccountService accounts;
    private final VerificationResendRateLimiter resendRateLimiter;

    public UserAccountsController(OperatorAccountService accounts,
                                  VerificationResendRateLimiter resendRateLimiter) {
        this.accounts = accounts;
        this.resendRateLimiter = resendRateLimiter;
    }

    /**
     * Enumeration-safe: response is always 202 regardless of whether the
     * email already exists. Side-effects (insert user OR send decoy mail)
     * happen inside the service.
     */
    @PostMapping("/signup")
    public Mono<ResponseEntity<Void>> signup(@RequestBody SignupRequestBody body,
                                             ServerWebExchange exchange) {
        if (body == null) {
            return Mono.error(new IllegalArgumentException("body is required"));
        }
        RequestMeta meta = meta(exchange);
        char[] pw = body.password() == null ? new char[0] : body.password().toCharArray();
        SignupRequest req = new SignupRequest(body.email(), body.displayName(), pw);
        return Mono.<SignupOutcome>fromCallable(() -> accounts.register(req, meta))
                .subscribeOn(Schedulers.boundedElastic())
                .map(o -> ResponseEntity.accepted().<Void>build());
    }

    /**
     * Token-bearer. On success returns the default workspace id so the SPA
     * can route the user straight into it after signing in.
     */
    @GetMapping("/verify")
    public Mono<ResponseEntity<VerifyResponse>> verify(@RequestParam("token") String token,
                                                       ServerWebExchange exchange) {
        RequestMeta meta = meta(exchange);
        return Mono.<VerifyOutcome>fromCallable(() -> accounts.verifyEmail(token, meta))
                .subscribeOn(Schedulers.boundedElastic())
                .map(out -> ResponseEntity.ok(new VerifyResponse(
                        out.userId().value(),
                        out.defaultWorkspaceId().value(),
                        out.redeemedInvitations().size())));
    }

    /**
     * Re-issue an email verification token for an account that hasn't
     * activated yet. Always 202 to keep the response enumeration-safe;
     * 429 only when the per-email or per-IP bucket is empty.
     */
    @PostMapping("/verify/resend")
    public Mono<ResponseEntity<Void>> resendVerification(@RequestBody EmailBody body,
                                                         ServerWebExchange exchange) {
        if (body == null || body.email() == null || body.email().isBlank()) {
            // Match other enumeration-safe endpoints: return 202 regardless,
            // skip the rate-limit ticket so probes can't drain the bucket
            // with empty bodies.
            return Mono.just(ResponseEntity.accepted().build());
        }
        RequestMeta meta = meta(exchange);
        var rl = resendRateLimiter.check(body.email(), meta.clientIp());
        if (rl instanceof VerificationResendRateLimiter.Outcome.Denied d) {
            log.info("verification-resend deny: rate_limited axis={} retryAfter={}s (email={}, ip={})",
                    d.axis(), d.retryAfterSeconds(), body.email(), meta.clientIp());
            return Mono.error(new RateLimitedException(d.axis(), d.retryAfterSeconds()));
        }
        return Mono.<Void>fromRunnable(() -> accounts.requestVerificationResend(body.email(), meta))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(ResponseEntity.accepted().<Void>build()));
    }

    @PostMapping("/password-reset/request")
    public Mono<ResponseEntity<Void>> requestReset(@RequestBody EmailBody body,
                                                   ServerWebExchange exchange) {
        if (body == null || body.email() == null) {
            return Mono.just(ResponseEntity.noContent().build());
        }
        RequestMeta meta = meta(exchange);
        return Mono.<Void>fromRunnable(() -> accounts.requestPasswordReset(body.email(), meta))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(ResponseEntity.noContent().build()));
    }

    @PostMapping("/password-reset/confirm")
    public Mono<ResponseEntity<Void>> confirmReset(@RequestBody ResetConfirmBody body,
                                                   ServerWebExchange exchange) {
        if (body == null) {
            return Mono.error(new IllegalArgumentException("body is required"));
        }
        RequestMeta meta = meta(exchange);
        char[] pw = body.newPassword() == null ? new char[0] : body.newPassword().toCharArray();
        return Mono.<Void>fromRunnable(() -> accounts.confirmPasswordReset(body.token(), pw, meta))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(ResponseEntity.status(HttpStatus.NO_CONTENT).<Void>build()));
    }

    // ---- helpers ------------------------------------------------------

    private static RequestMeta meta(ServerWebExchange exchange) {
        var addr = exchange.getRequest().getRemoteAddress();
        String ip = addr == null ? null : addr.getAddress().getHostAddress();
        String ua = exchange.getRequest().getHeaders().getFirst("User-Agent");
        return new RequestMeta(ip, ua, null);
    }

    // ---- DTOs ---------------------------------------------------------

    public record SignupRequestBody(String email, String displayName, String password) {}

    public record EmailBody(String email) {}

    public record ResetConfirmBody(String token, String newPassword) {}

    public record VerifyResponse(UUID userId, String defaultWorkspaceCode, int invitationsRedeemed) {}
}
