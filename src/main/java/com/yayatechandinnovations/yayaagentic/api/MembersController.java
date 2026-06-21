package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Membership;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.MembershipService;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.MembershipService.MemberSummary;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
 * Members of a workspace. Reads are open to {@link Role#VIEWER}+, writes
 * to {@link Role#ADMIN}+; the OWNER-only nuance (promote-to-OWNER,
 * demote-OWNER, remove-OWNER) lives inside {@link MembershipService}.
 */
@RestController
@RequestMapping("/v1/admin/workspaces/{idOrCode}/members")
@ConditionalOnProperty(name = "yaya.agentic.user-accounts.enabled", havingValue = "true")
public class MembersController {

    private final WorkspaceService workspaces;
    private final WorkspaceAccessGuard guard;
    private final MembershipService members;

    public MembersController(WorkspaceService workspaces,
                             WorkspaceAccessGuard guard,
                             MembershipService members) {
        this.workspaces = workspaces;
        this.guard = guard;
        this.members = members;
    }

    @GetMapping
    public Mono<ResponseEntity<List<MemberDto>>> list(@PathVariable("idOrCode") String idOrCode,
                                                      ServerWebExchange exchange) {
        UserId me = currentUserId(exchange);
        return Mono.fromCallable(() -> {
                    TenantId id = resolveOr404(idOrCode, me);
                    guard.require(me, id, Role.VIEWER);
                    return members.list(id).stream().map(MemberDto::of).toList();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok);
    }

    @PatchMapping("/{userId}")
    public Mono<ResponseEntity<MemberDto>> patch(@PathVariable("idOrCode") String idOrCode,
                                                 @PathVariable("userId") UUID userId,
                                                 @RequestBody RoleBody body,
                                                 ServerWebExchange exchange) {
        if (body == null || body.role() == null) {
            return Mono.error(new IllegalArgumentException("role is required"));
        }
        UserId me = currentUserId(exchange);
        RequestMeta meta = metaOf(exchange);
        return Mono.fromCallable(() -> {
                    TenantId id = resolveOr404(idOrCode, me);
                    Membership actor = guard.require(me, id, Role.ADMIN);
                    Role newRole;
                    try {
                        newRole = Role.valueOf(body.role());
                    } catch (IllegalArgumentException ex) {
                        throw new IllegalArgumentException("role must be OWNER, ADMIN, MEMBER, or VIEWER");
                    }
                    return members.changeRole(id, new UserId(userId), newRole, actor, meta);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .map(m -> ResponseEntity.ok(MemberDto.of(m)));
    }

    @DeleteMapping("/{userId}")
    public Mono<ResponseEntity<Void>> remove(@PathVariable("idOrCode") String idOrCode,
                                             @PathVariable("userId") UUID userId,
                                             ServerWebExchange exchange) {
        UserId me = currentUserId(exchange);
        RequestMeta meta = metaOf(exchange);
        return Mono.<Void>fromRunnable(() -> {
                    TenantId id = resolveOr404(idOrCode, me);
                    Membership actor = guard.require(me, id, Role.ADMIN);
                    members.remove(id, new UserId(userId), actor, meta);
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

    public record RoleBody(String role) {}

    public record MemberDto(UUID userId, String email, String displayName, String role, String addedAt) {
        static MemberDto of(MemberSummary m) {
            return new MemberDto(
                    m.userId().value(),
                    m.email(),
                    m.displayName(),
                    m.role().name(),
                    m.addedAt() == null ? null : m.addedAt().toString());
        }
    }
}
