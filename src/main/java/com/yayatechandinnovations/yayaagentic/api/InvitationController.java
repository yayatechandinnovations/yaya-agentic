package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.InvitationService;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.InvitationService.InvitationSummary;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.OperatorAccountService.RequestMeta;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.UserId;
import com.yayatechandinnovations.yayaagentic.operator_auth.web.OperatorContext;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.OperatorIdentity;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceAccessGuard;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceNotFoundException;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.UUID;

/**
 * Workspace-scoped invitation surface: ADMIN+ can send, list, and revoke.
 * The accept side lives on {@link com.yayatechandinnovations.yayaagentic.operator_auth.web.InvitationAcceptController}
 * because it's a token-bearer call from a recipient who may or may not be
 * signed in.
 */
@RestController
@RequestMapping("/v1/admin/workspaces/{idOrCode}/invitations")
@ConditionalOnProperty(name = "yaya.agentic.user-accounts.enabled", havingValue = "true")
public class InvitationController {

    private final WorkspaceService workspaces;
    private final WorkspaceAccessGuard guard;
    private final InvitationService invitations;

    public InvitationController(WorkspaceService workspaces,
                                WorkspaceAccessGuard guard,
                                InvitationService invitations) {
        this.workspaces = workspaces;
        this.guard = guard;
        this.invitations = invitations;
    }

    @GetMapping
    public Mono<ResponseEntity<List<InvitationDto>>> list(@PathVariable("idOrCode") String idOrCode,
                                                          ServerWebExchange exchange) {
        UserId me = currentUserId(exchange);
        return Mono.fromCallable(() -> {
                    TenantId id = resolveOr404(idOrCode, me);
                    guard.require(me, id, Role.ADMIN);
                    return invitations.listPending(id).stream().map(InvitationDto::of).toList();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok);
    }

    @PostMapping
    public Mono<ResponseEntity<InvitationDto>> create(@PathVariable("idOrCode") String idOrCode,
                                                      @RequestBody CreateRequest body,
                                                      ServerWebExchange exchange) {
        if (body == null || body.email() == null || body.role() == null) {
            return Mono.error(new IllegalArgumentException("email and role are required"));
        }
        UserId me = currentUserId(exchange);
        RequestMeta meta = metaOf(exchange);
        return Mono.fromCallable(() -> {
                    TenantId id = resolveOr404(idOrCode, me);
                    guard.require(me, id, Role.ADMIN);
                    Role role;
                    try {
                        role = Role.valueOf(body.role());
                    } catch (IllegalArgumentException ex) {
                        throw new IllegalArgumentException("role must be ADMIN, MEMBER, or VIEWER");
                    }
                    return invitations.create(id, body.email(), role, me, meta);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .map(inv -> ResponseEntity.status(HttpStatus.CREATED).body(InvitationDto.of(inv)));
    }

    @DeleteMapping("/{invitationId}")
    public Mono<ResponseEntity<Void>> revoke(@PathVariable("idOrCode") String idOrCode,
                                             @PathVariable("invitationId") UUID invitationId,
                                             ServerWebExchange exchange) {
        UserId me = currentUserId(exchange);
        RequestMeta meta = metaOf(exchange);
        return Mono.<Void>fromRunnable(() -> {
                    TenantId id = resolveOr404(idOrCode, me);
                    guard.require(me, id, Role.ADMIN);
                    invitations.revoke(invitationId, me, meta);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    // ---- helpers ------------------------------------------------------

    private UserId currentUserId(ServerWebExchange exchange) {
        return OperatorContext.current(exchange)
                .map(OperatorIdentity::userIdOf)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "no_operator_on_exchange"));
    }

    private TenantId resolveOr404(String idOrCode, UserId me) {
        return workspaces.resolveToId(idOrCode)
                .orElseThrow(() -> new WorkspaceNotFoundException(new TenantId(idOrCode == null ? "" : idOrCode), me));
    }

    private static RequestMeta metaOf(ServerWebExchange exchange) {
        var addr = exchange.getRequest().getRemoteAddress();
        String ip = addr == null ? null : addr.getAddress().getHostAddress();
        String ua = exchange.getRequest().getHeaders().getFirst("User-Agent");
        return new RequestMeta(ip, ua, null);
    }

    // ---- DTOs ---------------------------------------------------------

    public record CreateRequest(String email, String role) {}

    public record InvitationDto(
            UUID id,
            String workspaceId,
            String email,
            String role,
            UUID invitedBy,
            String expiresAt,
            String createdAt,
            String consumedAt
    ) {
        static InvitationDto of(InvitationSummary s) {
            return new InvitationDto(
                    s.id(),
                    s.workspaceId().value(),
                    s.email(),
                    s.role().name(),
                    s.invitedBy() == null ? null : s.invitedBy().value(),
                    s.expiresAt() == null ? null : s.expiresAt().toString(),
                    s.createdAt() == null ? null : s.createdAt().toString(),
                    s.consumedAt() == null ? null : s.consumedAt().toString());
        }
    }
}
