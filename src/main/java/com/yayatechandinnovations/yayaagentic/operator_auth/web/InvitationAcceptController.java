package com.yayatechandinnovations.yayaagentic.operator_auth.web;

import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.InvitationService;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.InvitationService.AcceptOutcome;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.OperatorAccountService.RequestMeta;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.UserId;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.OperatorIdentity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Optional;
import java.util.UUID;

/**
 * Invitation accept — token-bearer, session optional. Returns one of four
 * shapes matching {@link AcceptOutcome}; the UI decides whether to redirect
 * to the workspace, redirect to signup with the token preserved, or render
 * an error banner. See App-Flow §5.2 / §5.3.
 *
 * <p>Lives next to {@link AuthController} (token-bearer, no session
 * required) rather than under {@code /v1/admin/**} because the recipient
 * may not yet have an account.</p>
 */
@RestController
@RequestMapping("/v1/auth/invitations")
@ConditionalOnProperty(name = "yaya.agentic.user-accounts.enabled", havingValue = "true")
public class InvitationAcceptController {

    private final InvitationService invitations;

    public InvitationAcceptController(InvitationService invitations) {
        this.invitations = invitations;
    }

    @GetMapping("/accept")
    public Mono<ResponseEntity<AcceptDto>> accept(@RequestParam("token") String token,
                                                  ServerWebExchange exchange) {
        Optional<UserId> signedIn = OperatorContext.current(exchange)
                .map(OperatorIdentity::userIdOf);
        RequestMeta meta = metaOf(exchange);
        return Mono.fromCallable(() -> invitations.accept(token, signedIn, meta))
                .subscribeOn(Schedulers.boundedElastic())
                .map(InvitationAcceptController::render);
    }

    private static ResponseEntity<AcceptDto> render(AcceptOutcome outcome) {
        return switch (outcome) {
            case AcceptOutcome.Accepted a -> ResponseEntity.ok(
                    new AcceptDto("accepted",
                            a.userId() == null ? null : a.userId().value(),
                            a.workspace().value(),
                            a.role().name(),
                            null,
                            null));
            case AcceptOutcome.NeedsSignup s -> ResponseEntity.ok(
                    new AcceptDto("needs_signup",
                            null,
                            s.workspace().value(),
                            s.role().name(),
                            s.email(),
                            s.rawTokenForCarry()));
            case AcceptOutcome.Expired e -> ResponseEntity.status(HttpStatus.GONE)
                    .body(new AcceptDto("expired", null, null, null, null, null));
            case AcceptOutcome.Invalid i -> ResponseEntity.status(HttpStatus.GONE)
                    .body(new AcceptDto("invalid", null, null, null, null, null));
        };
    }

    private static RequestMeta metaOf(ServerWebExchange exchange) {
        var addr = exchange.getRequest().getRemoteAddress();
        String ip = addr == null ? null : addr.getAddress().getHostAddress();
        String ua = exchange.getRequest().getHeaders().getFirst("User-Agent");
        return new RequestMeta(ip, ua, null);
    }

    /**
     * Unified accept response.
     * <ul>
     *   <li>{@code status = accepted} → caller now has a membership.</li>
     *   <li>{@code status = needs_signup} → anon visitor; UI redirects
     *       to {@code /signup?invite=&lt;rawToken&gt;&email=&lt;email&gt;}.</li>
     *   <li>{@code status = expired | invalid} → 410 Gone.</li>
     * </ul>
     */
    public record AcceptDto(
            String status,
            UUID userId,
            String workspaceId,
            String role,
            String email,
            String rawTokenForCarry
    ) {}
}
