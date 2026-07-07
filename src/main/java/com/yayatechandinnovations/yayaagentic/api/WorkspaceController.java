package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.OperatorAccountService.RequestMeta;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.UserId;
import com.yayatechandinnovations.yayaagentic.operator_auth.apikeys.RejectForApiKeys;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * User-facing workspace surface. Every endpoint runs the {@link
 * WorkspaceAccessGuard} BEFORE business logic; cross-user access returns
 * 404 with the same body as "workspace does not exist."
 *
 * <p>Distinct from {@link TenantController}, which is the platform-admin
 * "every tenant in the system" view kept for backward compatibility.</p>
 */
@RestController
@RequestMapping("/v1/admin/workspaces")
@ConditionalOnProperty(name = "yaya.agentic.user-accounts.enabled", havingValue = "true")
public class WorkspaceController {

    private final WorkspaceService workspaces;
    private final WorkspaceAccessGuard guard;

    public WorkspaceController(WorkspaceService workspaces, WorkspaceAccessGuard guard) {
        this.workspaces = workspaces;
        this.guard = guard;
    }

    @GetMapping
    public Mono<ResponseEntity<List<WorkspaceListItemDto>>> listMine(ServerWebExchange exchange) {
        UserId me = currentUserId(exchange);
        return Mono.<List<WorkspaceListItemDto>>fromCallable(() -> workspaces.listMine(me)
                        .stream()
                        .map(WorkspaceListItemDto::of)
                        .toList())
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok);
    }

    @PostMapping
    public Mono<ResponseEntity<WorkspaceDto>> create(@RequestBody CreateRequest body,
                                                     ServerWebExchange exchange) {
        if (body == null || body.displayName() == null || body.displayName().isBlank()) {
            return Mono.error(new IllegalArgumentException("displayName is required"));
        }
        UserId me = currentUserId(exchange);
        RequestMeta meta = metaOf(exchange);
        return Mono.fromCallable(() -> workspaces.create(
                        me, body.displayName(), body.code(),
                        body.hostBaseUrl(),
                        body.hostBaseUrlAllowlist(),
                        body.inboundOriginAllowlist(),
                        meta))
                .subscribeOn(Schedulers.boundedElastic())
                .map(w -> ResponseEntity.status(HttpStatus.CREATED).body(WorkspaceDto.of(w)));
    }

    @GetMapping("/{idOrCode}")
    public Mono<ResponseEntity<WorkspaceDto>> get(@PathVariable("idOrCode") String idOrCode,
                                                  ServerWebExchange exchange) {
        UserId me = currentUserId(exchange);
        return Mono.fromCallable(() -> {
                    TenantId id = resolveOr404(idOrCode, me);
                    guard.require(me, id, Role.VIEWER);
                    return workspaces.findByIdOrCode(idOrCode)
                            .orElseThrow(() -> new WorkspaceNotFoundException(id, me));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .map(w -> ResponseEntity.ok(WorkspaceDto.of(w)));
    }

    @PatchMapping("/{idOrCode}")
    public Mono<ResponseEntity<WorkspaceDto>> patch(@PathVariable("idOrCode") String idOrCode,
                                                    @RequestBody PatchRequest body,
                                                    ServerWebExchange exchange) {
        if (body == null) return Mono.error(new IllegalArgumentException("body is required"));
        UserId me = currentUserId(exchange);
        RequestMeta meta = metaOf(exchange);
        return Mono.fromCallable(() -> {
                    TenantId id = resolveOr404(idOrCode, me);
                    guard.require(me, id, Role.ADMIN);
                    return workspaces.patch(
                            id,
                            body.displayName(),
                            body.hostBaseUrl(),
                            body.hostBaseUrlAllowlist(),
                            body.inboundOriginAllowlist(),
                            me, meta);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .map(w -> ResponseEntity.ok(WorkspaceDto.of(w)));
    }

    @DeleteMapping("/{idOrCode}")
    @RejectForApiKeys(reason = "api_key_cannot_delete_workspace")
    public Mono<ResponseEntity<Void>> archive(@PathVariable("idOrCode") String idOrCode,
                                              @RequestBody ArchiveRequest body,
                                              ServerWebExchange exchange) {
        if (body == null) return Mono.error(new IllegalArgumentException("body is required"));
        UserId me = currentUserId(exchange);
        RequestMeta meta = metaOf(exchange);
        return Mono.<Void>fromRunnable(() -> {
                    TenantId id = resolveOr404(idOrCode, me);
                    guard.require(me, id, Role.OWNER);
                    workspaces.archive(id, body.confirmCode(), me, meta);
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

    /** Resolve idOrCode → TenantId or 404. We never reveal "workspace exists but you don't have access" vs "doesn't exist." */
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

    /**
     * {@code displayName} is required. {@code code} is optional — when
     * supplied it is used verbatim (after validation) instead of being
     * derived from {@code displayName}, so the workspace code can match an
     * external system byte-for-byte. Invalid shapes return 400; collisions
     * return 409 {@code workspace_code_taken}.
     *
     * <p>The three URL fields are optional and mirror {@link PatchRequest} —
     * they let an operator set the outbound host + allowlists at creation so
     * an HTTP auth resolver / HTTP tool works on the first try instead of
     * failing {@code config_error} until General settings is opened. Same
     * validation as PATCH; a bad shape returns 400.
     */
    public record CreateRequest(
            String displayName,
            String code,
            String hostBaseUrl,
            List<String> hostBaseUrlAllowlist,
            List<String> inboundOriginAllowlist
    ) {}

    /**
     * Each field is optional ({@code null} → "leave untouched"). Empty
     * string in {@code hostBaseUrl} clears the value; an empty list in
     * either allowlist clears that allowlist. {@code displayName} when
     * present must be non-blank.
     */
    public record PatchRequest(
            String displayName,
            String hostBaseUrl,
            List<String> hostBaseUrlAllowlist,
            List<String> inboundOriginAllowlist
    ) {}

    public record ArchiveRequest(String confirmCode) {}

    public record WorkspaceDto(
            String id,
            String code,
            String displayName,
            String status,
            String hostBaseUrl,
            List<String> hostBaseUrlAllowlist,
            List<String> inboundOriginAllowlist,
            String archivedAt,
            String createdAt
    ) {
        static WorkspaceDto of(WorkspaceService.WorkspaceSummary s) {
            return new WorkspaceDto(
                    s.id(), s.code(), s.displayName(), s.status(),
                    s.hostBaseUrl(),
                    s.hostBaseUrlAllowlist() == null ? List.of() : s.hostBaseUrlAllowlist(),
                    s.inboundOriginAllowlist() == null ? List.of() : s.inboundOriginAllowlist(),
                    s.archivedAt() == null ? null : s.archivedAt().toString(),
                    s.createdAt() == null ? null : s.createdAt().toString());
        }
    }

    public record WorkspaceListItemDto(WorkspaceDto workspace, String role) {
        static WorkspaceListItemDto of(WorkspaceService.MyWorkspace m) {
            return new WorkspaceListItemDto(WorkspaceDto.of(m.workspace()), m.role().name());
        }
    }
}
