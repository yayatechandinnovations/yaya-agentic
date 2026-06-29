package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.auth.secrets.JwtSecretCipher;
import com.yayatechandinnovations.yayaagentic.channel.auth.AuthResolverRegistry;
import com.yayatechandinnovations.yayaagentic.channel.auth.AuthResolverTestProbe;
import com.yayatechandinnovations.yayaagentic.channel.auth.cache.AuthResolverDispatcher;
import com.yayatechandinnovations.yayaagentic.channel.auth.cache.AuthResolverTokenCache;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceAccessHelper;
import com.yayatechandinnovations.yayaagentic.persistence.ChannelAuthResolverEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChannelAuthResolverRepository;
import com.yayatechandinnovations.yayaagentic.tenant.TenantGuard;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/**
 * Admin CRUD for the per-tenant {@code channel_auth_resolvers} table. Lets
 * a workspace ADMIN register, rotate, test, and delete the identity
 * webhooks that resolve inbound channel callers to tenant-issued JWTs.
 *
 * <p>Secrets flow in via the write endpoints as plaintext (over TLS, one-
 * time). The GET surface returns metadata only, with a masked suffix on
 * {@code serviceSecretMasked} so the operator can confirm "yes, that's
 * the right secret" without recovering the value. Rotating sends the new
 * secret in the request body — same shape as creating one. There is NO
 * "show secret" endpoint by design.
 *
 * <p>The DELETE endpoint also pattern-evicts the resolver's cached JWTs
 * before returning; an invalidated cache survives a tenant reading a
 * stale binding row that briefly still points at the gone resolver.
 *
 * <p>See {@code docs/design/channel-auth-resolver-design.md} §11 (admin
 * surface), §13 (security non-negotiables 3 + 7).
 */
@RestController
@RequestMapping("/v1/admin/tenants/{tenantId}/auth-resolvers")
public class TenantAuthResolverController {

    private static final Set<String> ALLOWED_KINDS = Set.of("BEAN", "HTTP");
    private static final Set<String> ALLOWED_SERVICE_AUTH_KINDS =
            Set.of("NONE", "BEARER", "SIGNED_JWT");
    private static final Set<String> ALLOWED_ON_REFRESH = Set.of("REJECT", "DOWNGRADE_TO_ANONYMOUS");
    private static final Set<String> ALLOWED_STATUSES = Set.of("ACTIVE", "DEACTIVATED");

    private final TenantGuard tenantGuard;
    private final WorkspaceAccessHelper workspaceAccess;
    private final ChannelAuthResolverRepository repo;
    private final JwtSecretCipher cipher;
    private final AuthResolverRegistry registry;
    private final AuthResolverTestProbe probe;
    private final AuthResolverDispatcher dispatcher;

    public TenantAuthResolverController(TenantGuard tenantGuard,
                                        WorkspaceAccessHelper workspaceAccess,
                                        ChannelAuthResolverRepository repo,
                                        JwtSecretCipher cipher,
                                        AuthResolverRegistry registry,
                                        AuthResolverTestProbe probe,
                                        AuthResolverDispatcher dispatcher) {
        this.tenantGuard = tenantGuard;
        this.workspaceAccess = workspaceAccess;
        this.repo = repo;
        this.cipher = cipher;
        this.registry = registry;
        this.probe = probe;
        this.dispatcher = dispatcher;
    }

    @GetMapping
    public ListResponse list(@PathVariable("tenantId") String tenantId,
                             ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.VIEWER);
        tenantGuard.requireExists(tenantId);
        List<ResolverResponse> rows = repo.findByTenantIdOrderByNameAsc(tenantId).stream()
                .map(ResolverResponse::of)
                .toList();
        return new ListResponse(rows, registry.registeredBeanNames());
    }

    @GetMapping("/{name}")
    public ResolverResponse get(@PathVariable("tenantId") String tenantId,
                                @PathVariable("name") String name,
                                ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.VIEWER);
        tenantGuard.requireExists(tenantId);
        return ResolverResponse.of(loadOrThrow(tenantId, name));
    }

    @PostMapping
    @Transactional
    public ResponseEntity<ResolverResponse> create(
            @PathVariable("tenantId") String tenantId,
            @RequestBody ResolverRequest req,
            ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        validateCreate(req);

        ChannelAuthResolverEntity row = new ChannelAuthResolverEntity(
                tenantId, req.name().trim(),
                trimOrNull(req.description()),
                req.kind().trim());
        applyEditableFields(row, req);
        applySecret(row, req, true);

        ChannelAuthResolverEntity saved;
        try {
            saved = repo.save(row);
        } catch (DataIntegrityViolationException dup) {
            throw AdminApiException.conflict("auth_resolver_exists",
                    "an auth resolver named '" + req.name() + "' already exists in tenant '"
                            + tenantId + "'");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(ResolverResponse.of(saved));
    }

    /**
     * Update accepts a blank/missing {@code serviceSecret} to mean "leave
     * the existing secret as-is, only update metadata." Sending a value
     * rotates the secret in place — same row id, new ciphertext + iv.
     */
    @PutMapping("/{name}")
    @Transactional
    public ResolverResponse update(@PathVariable("tenantId") String tenantId,
                                   @PathVariable("name") String name,
                                   @RequestBody ResolverRequest req,
                                   ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        validateUpdate(req, name);

        ChannelAuthResolverEntity row = loadOrThrow(tenantId, name);
        // Renaming is allowed but rare; uniqueness is enforced by the DB.
        if (!row.getName().equals(req.name().trim())) {
            throw AdminApiException.badRequest("name_immutable",
                    "renaming an auth resolver is not supported — delete and recreate");
        }
        if (!row.getKind().equals(req.kind().trim())) {
            throw AdminApiException.badRequest("kind_immutable",
                    "changing kind (BEAN ↔ HTTP) is not supported — delete and recreate");
        }
        row.setDescription(trimOrNull(req.description()));
        applyEditableFields(row, req);
        applySecret(row, req, false);
        ChannelAuthResolverEntity saved = repo.save(row);
        return ResolverResponse.of(saved);
    }

    @DeleteMapping("/{name}")
    @Transactional
    public ResponseEntity<DeleteResponse> delete(@PathVariable("tenantId") String tenantId,
                                                 @PathVariable("name") String name,
                                                 ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        ChannelAuthResolverEntity row = loadOrThrow(tenantId, name);
        repo.delete(row);
        // §8.5 — evict ALL cached JWTs for the tenant. Per-resolver eviction
        // would require a secondary Redis index; in v1 the safer move is to
        // wipe the tenant scope and let subsequent sessions re-populate.
        long evicted = dispatcher.evictAllForTenant(new Ids.TenantId(tenantId));
        return ResponseEntity.ok(new DeleteResponse(name, evicted));
    }

    /** Operator-facing debug surface — see {@link AuthResolverTestProbe}. */
    @PostMapping("/{name}/test")
    public AuthResolverTestProbe.ProbeResult test(@PathVariable("tenantId") String tenantId,
                                                  @PathVariable("name") String name,
                                                  @RequestBody(required = false)
                                                  AuthResolverTestProbe.ProbeInput input,
                                                  ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireExists(tenantId);
        ChannelAuthResolverEntity row = loadOrThrow(tenantId, name);
        return probe.probe(row, input);
    }

    /** §8.4 — explicit cache invalidate. */
    @PostMapping("/invalidate-cache")
    public InvalidateCacheResponse invalidateCache(
            @PathVariable("tenantId") String tenantId,
            @RequestBody InvalidateCacheRequest req,
            ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireExists(tenantId);
        if (req == null) {
            throw AdminApiException.badRequest("missing_body",
                    "invalidate body required");
        }

        if (req.allForTenant() != null && req.allForTenant()) {
            long count = dispatcher.evictAllForTenant(new Ids.TenantId(tenantId));
            return new InvalidateCacheResponse(true, count);
        }

        if (req.callerPhone() == null || req.callerPhone().isBlank()
                || req.senderId() == null || req.senderId().isBlank()) {
            throw AdminApiException.badRequest("missing_caller",
                    "either allForTenant=true OR (callerPhone, senderId) must be supplied");
        }
        dispatcher.evict(new Ids.TenantId(tenantId),
                new SenderId(req.senderId().trim()),
                req.callerPhone().trim());
        return new InvalidateCacheResponse(false, 1);
    }

    // ---- Helpers ------------------------------------------------------------

    private ChannelAuthResolverEntity loadOrThrow(String tenantId, String name) {
        return repo.findByTenantIdAndName(tenantId, name)
                .orElseThrow(() -> AdminApiException.notFound("auth_resolver_not_found",
                        "no auth resolver named '" + name + "' in tenant '" + tenantId + "'"));
    }

    private void applyEditableFields(ChannelAuthResolverEntity row, ResolverRequest req) {
        if ("BEAN".equals(req.kind())) {
            row.setBeanName(req.beanName().trim());
            row.setHttpPath(null);
            row.setServiceAuthKind(null);
            row.setServiceJwtKid(null);
            row.setServiceJwtAudience(null);
        } else {
            row.setHttpPath(req.httpPath().trim());
            row.setBeanName(null);
            row.setServiceAuthKind(req.serviceAuthKind() == null
                    ? "NONE" : req.serviceAuthKind().trim());
            row.setServiceJwtKid(trimOrNull(req.serviceJwtKid()));
            row.setServiceJwtAudience(trimOrNull(req.serviceJwtAudience()));
            row.setTimeoutMs(req.timeoutMs() == null ? 3000 : req.timeoutMs());
        }
        if (req.cacheEnabled() != null) row.setCacheEnabled(req.cacheEnabled());
        if (req.maxCacheSeconds() != null) row.setMaxCacheSeconds(req.maxCacheSeconds());
        if (req.refreshWindowSeconds() != null)
            row.setRefreshWindowSeconds(req.refreshWindowSeconds());
        if (req.maxTokenAgeSeconds() != null)
            row.setMaxTokenAgeSeconds(req.maxTokenAgeSeconds());
        if (req.onRefreshFailure() != null && !req.onRefreshFailure().isBlank())
            row.setOnRefreshFailure(req.onRefreshFailure().trim());
        if (req.status() != null && !req.status().isBlank())
            row.setStatus(req.status().trim());
    }

    private void applySecret(ChannelAuthResolverEntity row, ResolverRequest req,
                             boolean creating) {
        if ("BEAN".equals(req.kind())) return;
        String secret = req.serviceSecret();
        boolean providing = secret != null && !secret.isBlank();
        String kind = row.getServiceAuthKind();
        boolean needsSecret = "BEARER".equals(kind) || "SIGNED_JWT".equals(kind);

        if (providing) {
            requireCipherConfigured();
            JwtSecretCipher.Encrypted enc = cipher.encrypt(secret.trim());
            row.setServiceSecretCiphertext(enc.ciphertext());
            row.setServiceSecretIv(enc.iv());
        } else if (creating && needsSecret) {
            throw AdminApiException.badRequest("missing_service_secret",
                    "service_auth_kind '" + kind + "' requires serviceSecret on create");
        }
        if ("NONE".equals(kind)) {
            row.setServiceSecretCiphertext(null);
            row.setServiceSecretIv(null);
        }
    }

    private void validateCreate(ResolverRequest req) {
        validateCommon(req);
        if (req.kind() == null || req.kind().isBlank() || !ALLOWED_KINDS.contains(req.kind().trim())) {
            throw AdminApiException.badRequest("invalid_kind",
                    "kind must be one of " + ALLOWED_KINDS);
        }
        if ("BEAN".equals(req.kind().trim())) {
            if (req.beanName() == null || req.beanName().isBlank()) {
                throw AdminApiException.badRequest("missing_bean_name",
                        "beanName is required for kind=BEAN");
            }
            // Check the bean is actually registered before saving.
            if (!registry.registeredBeanNames().contains(req.beanName().trim())) {
                throw AdminApiException.badRequest("unknown_bean",
                        "no AuthResolver bean named '" + req.beanName()
                                + "' is registered on this deployment (available: "
                                + registry.registeredBeanNames() + ")");
            }
        } else {
            validateHttpFields(req);
        }
    }

    private void validateUpdate(ResolverRequest req, String pathName) {
        validateCommon(req);
        if (req.name() == null || !req.name().trim().equals(pathName)) {
            throw AdminApiException.badRequest("name_path_mismatch",
                    "name in body must match URL path");
        }
        if ("HTTP".equals(req.kind())) validateHttpFields(req);
    }

    private void validateCommon(ResolverRequest req) {
        if (req == null) throw AdminApiException.badRequest("missing_body",
                "request body required");
        if (req.name() == null || req.name().isBlank() || req.name().length() > 64) {
            throw AdminApiException.badRequest("invalid_name",
                    "name is required and must be 1..64 chars");
        }
        if (req.onRefreshFailure() != null
                && !ALLOWED_ON_REFRESH.contains(req.onRefreshFailure().trim())) {
            throw AdminApiException.badRequest("invalid_on_refresh_failure",
                    "onRefreshFailure must be one of " + ALLOWED_ON_REFRESH);
        }
        if (req.status() != null && !req.status().isBlank()
                && !ALLOWED_STATUSES.contains(req.status().trim())) {
            throw AdminApiException.badRequest("invalid_status",
                    "status must be one of " + ALLOWED_STATUSES);
        }
        if (req.refreshWindowSeconds() != null
                && (req.refreshWindowSeconds() < 0 || req.refreshWindowSeconds() > 600)) {
            throw AdminApiException.badRequest("invalid_refresh_window",
                    "refreshWindowSeconds must be in [0, 600]");
        }
        if (req.maxTokenAgeSeconds() != null
                && (req.maxTokenAgeSeconds() < 60 || req.maxTokenAgeSeconds() > 86400)) {
            throw AdminApiException.badRequest("invalid_max_token_age",
                    "maxTokenAgeSeconds must be in [60, 86400]");
        }
        if (req.timeoutMs() != null && (req.timeoutMs() < 100 || req.timeoutMs() > 30000)) {
            throw AdminApiException.badRequest("invalid_timeout",
                    "timeoutMs must be in [100, 30000]");
        }
    }

    private void validateHttpFields(ResolverRequest req) {
        if (req.httpPath() == null || req.httpPath().isBlank()) {
            throw AdminApiException.badRequest("missing_http_path",
                    "httpPath is required for kind=HTTP");
        }
        String path = req.httpPath().trim();
        if (!path.startsWith("/")) {
            throw AdminApiException.badRequest("invalid_http_path",
                    "httpPath must start with '/'");
        }
        if (path.contains("://") || path.contains("?") || path.contains("#")) {
            throw AdminApiException.badRequest("invalid_http_path",
                    "httpPath must not contain '://', '?', or '#'");
        }
        if (req.serviceAuthKind() != null
                && !req.serviceAuthKind().isBlank()
                && !ALLOWED_SERVICE_AUTH_KINDS.contains(req.serviceAuthKind().trim())) {
            throw AdminApiException.badRequest("invalid_service_auth_kind",
                    "serviceAuthKind must be one of " + ALLOWED_SERVICE_AUTH_KINDS);
        }
        // kid/audience are optional even for SIGNED_JWT — some verifiers use a
        // single shared secret with no `kid` selection. Bound-check only.
        if (req.serviceJwtKid() != null && req.serviceJwtKid().trim().length() > 128) {
            throw AdminApiException.badRequest("invalid_service_jwt_kid",
                    "serviceJwtKid must be at most 128 chars");
        }
        if (req.serviceJwtAudience() != null && req.serviceJwtAudience().trim().length() > 255) {
            throw AdminApiException.badRequest("invalid_service_jwt_audience",
                    "serviceJwtAudience must be at most 255 chars");
        }
    }

    private void requireCipherConfigured() {
        if (!cipher.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "secret cipher is not configured — set yaya.agentic.secrets.encryption-key "
                            + "(env YAYA_AGENTIC_SECRETS_ENCRYPTION_KEY) before writing auth-resolver secrets.");
        }
    }

    private static String trimOrNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String maskSecret(String ciphertext) {
        if (ciphertext == null || ciphertext.length() < 4) return null;
        String tail = ciphertext.substring(Math.max(0, ciphertext.length() - 4));
        return "••••••••••••" + tail;
    }

    // ---- DTOs ---------------------------------------------------------------

    public record ResolverRequest(
            String name,
            String description,
            String kind,                         // 'BEAN' | 'HTTP'
            String beanName,                     // BEAN only
            String httpPath,                     // HTTP only
            String serviceAuthKind,              // 'NONE' | 'BEARER' | 'SIGNED_JWT'
            String serviceSecret,                // plaintext; only on write, never returned
            String serviceJwtKid,                // JOSE `kid` for SIGNED_JWT; not a secret
            String serviceJwtAudience,           // overrides `aud`; null = tenant id
            Integer timeoutMs,
            Boolean cacheEnabled,
            Integer maxCacheSeconds,
            Integer refreshWindowSeconds,
            Integer maxTokenAgeSeconds,
            String onRefreshFailure,             // 'REJECT' | 'DOWNGRADE_TO_ANONYMOUS'
            String status                        // 'ACTIVE' | 'DEACTIVATED'
    ) {}

    /** Response shape — deliberately omits the plaintext secret. */
    public record ResolverResponse(
            Long id,
            String tenantId,
            String name,
            String description,
            String kind,
            String beanName,
            String httpPath,
            String serviceAuthKind,
            String serviceSecretMasked,
            String serviceJwtKid,                // cleartext — not a secret (§5)
            String serviceJwtAudience,
            Integer timeoutMs,
            Boolean cacheEnabled,
            Integer maxCacheSeconds,
            Integer refreshWindowSeconds,
            Integer maxTokenAgeSeconds,
            String onRefreshFailure,
            String status,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        public static ResolverResponse of(ChannelAuthResolverEntity e) {
            return new ResolverResponse(
                    e.getId(), e.getTenantId(), e.getName(), e.getDescription(),
                    e.getKind(), e.getBeanName(), e.getHttpPath(),
                    e.getServiceAuthKind(),
                    maskSecret(e.getServiceSecretCiphertext()),
                    e.getServiceJwtKid(), e.getServiceJwtAudience(),
                    e.getTimeoutMs(), e.isCacheEnabled(), e.getMaxCacheSeconds(),
                    e.getRefreshWindowSeconds(), e.getMaxTokenAgeSeconds(),
                    e.getOnRefreshFailure(), e.getStatus(),
                    e.getCreatedAt(), e.getUpdatedAt());
        }
    }

    public record ListResponse(List<ResolverResponse> resolvers,
                               List<String> registeredBeanNames) {}

    public record DeleteResponse(String name, long cacheEntriesEvicted) {}

    public record InvalidateCacheRequest(String callerPhone, String senderId,
                                         Boolean allForTenant) {}

    public record InvalidateCacheResponse(boolean tenantScopeEvicted, long count) {}
}
