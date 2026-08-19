package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.OperatorAccountService;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.OperatorAccountService.RequestMeta;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.User;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.UserId;
import com.yayatechandinnovations.yayaagentic.operator_auth.apikeys.ApiKey;
import com.yayatechandinnovations.yayaagentic.operator_auth.apikeys.ApiKeyNotFoundException;
import com.yayatechandinnovations.yayaagentic.operator_auth.apikeys.ApiKeyService;
import com.yayatechandinnovations.yayaagentic.operator_auth.apikeys.RejectForApiKeys;
import com.yayatechandinnovations.yayaagentic.operator_auth.web.OperatorContext;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.OperatorIdentity;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceAccessGuard;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceAccessHelper;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceNotFoundException;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceService;
import com.yayatechandinnovations.yayaagentic.persistence.AuditApiKeyEventEntity;
import com.yayatechandinnovations.yayaagentic.persistence.AuditApiKeyEventRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/**
 * Workspace-scoped API-key management (§8). Five endpoints + audit. Every
 * mutation runs the workspace access check first; cross-workspace access
 * returns 404 (never 403). The secret is returned exactly once, by
 * {@link #create}, and never echoed again.
 *
 * <p>{@code POST} (mint) is {@link RejectForApiKeys}: a key cannot mint another
 * key. List/get/patch/revoke/audit remain reachable by an ADMIN+ key.</p>
 */
@RestController
@RequestMapping("/v1/admin/workspaces/{idOrCode}/api-keys")
@ConditionalOnProperty(name = "yaya.agentic.user-accounts.enabled", havingValue = "true")
public class ApiKeyController {

    private static final int AUDIT_PAGE_MAX = 100;

    private final ApiKeyService service;
    private final WorkspaceAccessHelper workspaceAccess;
    private final WorkspaceAccessGuard guard;
    private final WorkspaceService workspaces;
    private final OperatorAccountService accounts;
    private final AuditApiKeyEventRepository auditRepo;

    public ApiKeyController(ApiKeyService service,
                           WorkspaceAccessHelper workspaceAccess,
                           WorkspaceAccessGuard guard,
                           WorkspaceService workspaces,
                           OperatorAccountService accounts,
                           AuditApiKeyEventRepository auditRepo) {
        this.service = service;
        this.workspaceAccess = workspaceAccess;
        this.guard = guard;
        this.workspaces = workspaces;
        this.accounts = accounts;
        this.auditRepo = auditRepo;
    }

    @GetMapping
    public Mono<ResponseEntity<List<ApiKeyDto>>> list(@PathVariable("idOrCode") String idOrCode,
                                                      ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
                    TenantId ws = new TenantId(workspaceAccess.require(idOrCode, exchange, Role.VIEWER));
                    return service.list(ws).stream().map(this::toDto).toList();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok);
    }

    @GetMapping("/{keyId}")
    public Mono<ResponseEntity<ApiKeyDto>> get(@PathVariable("idOrCode") String idOrCode,
                                               @PathVariable("keyId") UUID keyId,
                                               ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
                    TenantId ws = new TenantId(workspaceAccess.require(idOrCode, exchange, Role.VIEWER));
                    return toDto(service.get(ws, keyId).orElseThrow(ApiKeyNotFoundException::new));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok);
    }

    @PostMapping
    @RejectForApiKeys(reason = "api_key_cannot_mint_keys")
    public Mono<ResponseEntity<CreateResponse>> create(@PathVariable("idOrCode") String idOrCode,
                                                       @RequestBody CreateRequest body,
                                                       ServerWebExchange exchange) {
        if (body == null) return Mono.error(new IllegalArgumentException("body is required"));
        return Mono.fromCallable(() -> {
                    UserId me = currentUserId(exchange);
                    TenantId ws = resolveOr404(idOrCode, me);
                    // guard.require gives both the ADMIN+ gate and the creator's
                    // own role tier, which bounds the role they may mint (§6.2).
                    Role creatorRole = guard.require(me, ws, Role.ADMIN).role();
                    Role requested = parseRole(body.role());
                    OffsetDateTime expiresAt = parseExpiry(body.expiresAt());
                    ApiKeyService.Minted minted = service.mint(
                            ws, body.name(), requested, expiresAt, me, creatorRole, metaOf(exchange),
                            Boolean.TRUE.equals(body.platformScope()));
                    return new CreateResponse(
                            minted.key().id().toString(),
                            minted.key().name(),
                            minted.key().keyPrefix(),
                            minted.key().role().name(),
                            iso(minted.key().createdAt()),
                            iso(minted.key().expiresAt()),
                            minted.key().platformScope(),
                            minted.secret());
                })
                .subscribeOn(Schedulers.boundedElastic())
                .map(r -> ResponseEntity.status(HttpStatus.CREATED).body(r));
    }

    @PatchMapping("/{keyId}")
    public Mono<ResponseEntity<ApiKeyDto>> rename(@PathVariable("idOrCode") String idOrCode,
                                                  @PathVariable("keyId") UUID keyId,
                                                  @RequestBody PatchRequest body,
                                                  ServerWebExchange exchange) {
        if (body == null) return Mono.error(new IllegalArgumentException("body is required"));
        return Mono.fromCallable(() -> {
                    TenantId ws = new TenantId(workspaceAccess.require(idOrCode, exchange, Role.ADMIN));
                    return toDto(service.rename(ws, keyId, body.name(), auditActor(exchange), metaOf(exchange)));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok);
    }

    @DeleteMapping("/{keyId}")
    public Mono<ResponseEntity<Void>> revoke(@PathVariable("idOrCode") String idOrCode,
                                             @PathVariable("keyId") UUID keyId,
                                             ServerWebExchange exchange) {
        return Mono.<Void>fromRunnable(() -> {
                    TenantId ws = new TenantId(workspaceAccess.require(idOrCode, exchange, Role.ADMIN));
                    service.revoke(ws, keyId, auditActor(exchange), metaOf(exchange));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    @GetMapping("/{keyId}/audit")
    public Mono<ResponseEntity<List<AuditDto>>> audit(@PathVariable("idOrCode") String idOrCode,
                                                      @PathVariable("keyId") UUID keyId,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "50") int size,
                                                      ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
                    TenantId ws = new TenantId(workspaceAccess.require(idOrCode, exchange, Role.ADMIN));
                    // 404 if the key isn't in this workspace — before exposing audit.
                    service.get(ws, keyId).orElseThrow(ApiKeyNotFoundException::new);
                    int capped = Math.min(Math.max(1, size), AUDIT_PAGE_MAX);
                    return auditRepo.findByApiKeyIdOrderByOccurredAtDesc(
                                    keyId, PageRequest.of(Math.max(0, page), capped))
                            .map(ApiKeyController::toAuditDto)
                            .getContent();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok);
    }

    // ---- helpers ------------------------------------------------------

    private UserId currentUserId(ServerWebExchange exchange) {
        return OperatorContext.current(exchange)
                .map(OperatorIdentity::userIdOf)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "no_operator_on_exchange"));
    }

    /**
     * Audit actor for a mutation. For a cookie session it's the signed-in user.
     * For a key-initiated mutation (revoke/rename) it's the key's creator if
     * that user still exists, else null — never the fail-closed
     * {@code API_KEY_NO_CREATOR} id (which has no users row and would violate
     * the audit FK).
     */
    private UserId auditActor(ServerWebExchange exchange) {
        var op = OperatorContext.current(exchange)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "no_operator_on_exchange"));
        if (op.source() == com.yayatechandinnovations.yayaagentic.operator_auth.Operator.Source.API_KEY) {
            Object uid = op.attributes() == null ? null : op.attributes().get("user_id");
            if (uid instanceof String s && !s.isBlank()) {
                try {
                    return UserId.parse(s);
                } catch (IllegalArgumentException ignored) {
                    return null;   // creator deleted
                }
            }
            return null;
        }
        return OperatorIdentity.userIdOf(op);
    }

    private TenantId resolveOr404(String idOrCode, UserId me) {
        return workspaces.resolveToId(idOrCode)
                .orElseThrow(() -> new WorkspaceNotFoundException(new TenantId(idOrCode == null ? "" : idOrCode), me));
    }

    private static Role parseRole(String role) {
        if (role == null || role.isBlank()) throw new IllegalArgumentException("role is required");
        try {
            Role r = Role.valueOf(role.trim().toUpperCase());
            if (r == Role.PLATFORM_ADMIN) throw new IllegalArgumentException("invalid role");
            return r;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("role must be one of OWNER, ADMIN, MEMBER, VIEWER");
        }
    }

    private static OffsetDateTime parseExpiry(String expiresAt) {
        if (expiresAt == null || expiresAt.isBlank()) return null;
        try {
            return OffsetDateTime.parse(expiresAt.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("expiresAt must be an ISO-8601 timestamp");
        }
    }

    private ApiKeyDto toDto(ApiKey k) {
        return new ApiKeyDto(
                k.id().toString(),
                k.name(),
                k.keyPrefix(),
                k.role().name(),
                createdByLabel(k.createdByUserId()),
                iso(k.createdAt()),
                iso(k.expiresAt()),
                iso(k.lastUsedAt()),
                k.lastUsedIp(),
                k.status().name().toLowerCase(),
                k.platformScope());
    }

    private String createdByLabel(UserId createdBy) {
        if (createdBy == null) return "(deleted user)";
        return accounts.findById(createdBy).map(User::displayName).orElse("(deleted user)");
    }

    private static AuditDto toAuditDto(AuditApiKeyEventEntity e) {
        return new AuditDto(
                e.getKind(),
                e.getDetailJson(),
                e.getClientIp(),   // populated only for USE_DENIED; hashed events expose nothing
                iso(e.getOccurredAt()));
    }

    private static RequestMeta metaOf(ServerWebExchange exchange) {
        var addr = exchange.getRequest().getRemoteAddress();
        String ip = addr == null || addr.getAddress() == null ? null : addr.getAddress().getHostAddress();
        String ua = exchange.getRequest().getHeaders().getFirst("User-Agent");
        return new RequestMeta(ip, ua, null);
    }

    private static String iso(OffsetDateTime t) {
        return t == null ? null : t.toString();
    }

    // ---- DTOs ---------------------------------------------------------

    /** {@code platformScope} requires a platform-admin creator (gap B). */
    public record CreateRequest(String name, String role, String expiresAt, Boolean platformScope) {}

    public record PatchRequest(String name) {}

    /** The only response that carries {@code secret}. */
    public record CreateResponse(String id, String name, String keyPrefix, String role,
                                 String createdAt, String expiresAt, boolean platformScope,
                                 String secret) {}

    public record ApiKeyDto(String id, String name, String keyPrefix, String role,
                            String createdBy, String createdAt, String expiresAt,
                            String lastUsedAt, String lastUsedIp, String status,
                            boolean platformScope) {}

    public record AuditDto(String kind, String detail, String clientIp, String occurredAt) {}
}
