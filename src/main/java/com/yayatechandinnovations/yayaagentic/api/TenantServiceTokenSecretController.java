package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.auth.secrets.JwtSecretCipher;
import com.yayatechandinnovations.yayaagentic.auth.service_token.TenantServiceTokenSecretChangedEvent;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceAccessHelper;
import com.yayatechandinnovations.yayaagentic.persistence.TenantServiceTokenSecretEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantServiceTokenSecretRepository;
import com.yayatechandinnovations.yayaagentic.tenant.TenantGuard;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Admin surface for the per-tenant service-token HMAC secret (the
 * credential behind {@code X-Yaya-Service-Token} callbacks, e.g. a
 * tenant's {@code system.reply} → integration callback loop). Replaces
 * the static {@code yaya.agentic.auth.service-token.tenant-secrets}
 * config map as the primary source so new tenants need no restart to
 * onboard machine-to-machine callbacks.
 *
 * <p>Singleton-per-tenant resource, hence PUT (set or rotate) rather
 * than the collection CRUD of {@code jwt-secrets}. The GET surface
 * reports presence + rotation time only — the secret itself never
 * leaves this controller.</p>
 */
@RestController
@RequestMapping("/v1/admin/tenants/{tenantId}/service-token-secret")
public class TenantServiceTokenSecretController {

    private final TenantGuard tenantGuard;
    private final TenantServiceTokenSecretRepository repo;
    private final JwtSecretCipher cipher;
    private final ApplicationEventPublisher events;
    private final WorkspaceAccessHelper workspaceAccess;

    public TenantServiceTokenSecretController(TenantGuard tenantGuard,
                                              TenantServiceTokenSecretRepository repo,
                                              JwtSecretCipher cipher,
                                              ApplicationEventPublisher events,
                                              WorkspaceAccessHelper workspaceAccess) {
        this.tenantGuard = tenantGuard;
        this.repo = repo;
        this.cipher = cipher;
        this.events = events;
        this.workspaceAccess = workspaceAccess;
    }

    @GetMapping
    public StatusResponse status(@PathVariable("tenantId") String tenantId,
                                 ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireExists(tenantId);
        return repo.findById(tenantId)
                .map(e -> new StatusResponse(true, iso(e.getRotatedAt())))
                .orElseGet(() -> new StatusResponse(false, null));
    }

    @PutMapping
    @Transactional
    public ResponseEntity<StatusResponse> put(@PathVariable("tenantId") String tenantId,
                                              @RequestBody SetRequest req,
                                              ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireExists(tenantId);
        if (req == null || req.secret() == null || req.secret().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "secret is required");
        }
        if (req.secret().length() < 32) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "secret must be at least 32 characters (use e.g. `openssl rand -hex 32`)");
        }
        if (!cipher.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "secret cipher not configured — set yaya.agentic.secrets.encryption-key");
        }
        JwtSecretCipher.Encrypted enc = cipher.encrypt(req.secret().trim());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        boolean created = repo.findById(tenantId)
                .map(existing -> {
                    existing.rotate(enc.ciphertext(), enc.iv(), now);
                    repo.save(existing);
                    return false;
                })
                .orElseGet(() -> {
                    repo.save(new TenantServiceTokenSecretEntity(
                            tenantId, enc.ciphertext(), enc.iv(), now));
                    return true;
                });
        events.publishEvent(new TenantServiceTokenSecretChangedEvent(tenantId));
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK)
                .body(new StatusResponse(true, iso(now)));
    }

    @DeleteMapping
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable("tenantId") String tenantId,
                                       ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireExists(tenantId);
        repo.deleteById(tenantId);
        events.publishEvent(new TenantServiceTokenSecretChangedEvent(tenantId));
        return ResponseEntity.noContent().build();
    }

    private static String iso(OffsetDateTime t) {
        return t == null ? null : t.toInstant().toString();
    }

    public record SetRequest(String secret) {}

    /** Presence + rotation metadata only — never the secret. */
    public record StatusResponse(boolean present, String rotatedAt) {}
}
