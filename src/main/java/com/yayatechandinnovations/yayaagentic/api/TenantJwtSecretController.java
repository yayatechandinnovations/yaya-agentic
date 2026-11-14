package com.yayatechandinnovations.yayaagentic.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.auth.jwt.TenantJwtSecretsChangedEvent;
import com.yayatechandinnovations.yayaagentic.auth.secrets.JwtSecretCipher;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceAccessHelper;
import com.yayatechandinnovations.yayaagentic.persistence.TenantJwtSecretEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantJwtSecretRepository;
import com.yayatechandinnovations.yayaagentic.tenant.TenantGuard;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Admin CRUD for the per-tenant HMAC-signed JWT trust list. Lets a
 * tenant accept HS256-signed JWTs from an internal identity service
 * without standing up an OIDC IdP. Plaintext secrets only flow in via
 * the write endpoints — the GET surface returns metadata only, never
 * the secret itself.
 *
 * <p>Every write publishes {@link TenantJwtSecretsChangedEvent} so
 * {@code SharedSecretJwtAuthenticator}'s per-tenant verifier cache is
 * evicted before the next auth attempt.</p>
 */
@RestController
@RequestMapping("/v1/admin/tenants/{tenantId}/jwt-secrets")
public class TenantJwtSecretController {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};

    private final TenantGuard tenantGuard;
    private final TenantJwtSecretRepository repo;
    private final JwtSecretCipher cipher;
    private final ObjectMapper json;
    private final ApplicationEventPublisher events;
    private final WorkspaceAccessHelper workspaceAccess;

    public TenantJwtSecretController(TenantGuard tenantGuard,
                                     TenantJwtSecretRepository repo,
                                     JwtSecretCipher cipher,
                                     ObjectMapper json,
                                     ApplicationEventPublisher events,
                                     WorkspaceAccessHelper workspaceAccess) {
        this.tenantGuard = tenantGuard;
        this.repo = repo;
        this.cipher = cipher;
        this.json = json;
        this.events = events;
        this.workspaceAccess = workspaceAccess;
    }

    @GetMapping
    public List<SecretResponse> list(@PathVariable("tenantId") String tenantId,
                                     ServerWebExchange exchange) {
        // Returned value equals tenantId (path variable IS the tenant id);
        // we call this purely for the 404-or-allow side effect.
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireExists(tenantId);
        return repo.findByTenantId(tenantId).stream()
                .map(e -> SecretResponse.of(e, parseAudiences(e.getAudiencesJson()),
                        parseRequiredClaims(e.getRequiredClaimsJson())))
                .toList();
    }

    @PostMapping
    @Transactional
    public ResponseEntity<SecretResponse> create(@PathVariable("tenantId") String tenantId,
                                                 @RequestBody SecretRequest req,
                                                 ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        requireCipherConfigured();
        validateCreate(req);

        JwtSecretCipher.Encrypted encrypted = cipher.encrypt(req.secret().trim());
        TenantJwtSecretEntity entity = new TenantJwtSecretEntity(
                tenantId, req.iss().trim(),
                encrypted.ciphertext(), encrypted.iv(),
                writeAudiences(req.audiences()));
        applyBinding(entity, req);
        TenantJwtSecretEntity saved;
        try {
            saved = repo.save(entity);
        } catch (DataIntegrityViolationException dup) {
            throw AdminApiException.conflict("jwt_secret_exists",
                    "a jwt secret for iss '" + req.iss() + "' is already registered for tenant '"
                            + tenantId + "'");
        }
        events.publishEvent(new TenantJwtSecretsChangedEvent(tenantId));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SecretResponse.of(saved,
                        req.audiences() == null ? List.of() : req.audiences(),
                        parseRequiredClaims(saved.getRequiredClaimsJson())));
    }

    /**
     * Update accepts a blank/missing {@code secret} to mean "leave the
     * existing secret as-is, only update iss/audiences". Sending a value
     * rotates the secret in place — same row id, new ciphertext + iv.
     */
    @PutMapping("/{id}")
    @Transactional
    public SecretResponse update(@PathVariable("tenantId") String tenantId,
                                 @PathVariable("id") Long id,
                                 @RequestBody SecretRequest req,
                                 ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        validateMetadata(req);

        TenantJwtSecretEntity entity = repo.findById(id)
                .filter(e -> e.getTenantId().equals(tenantId))
                .orElseThrow(() -> AdminApiException.notFound("jwt_secret_not_found",
                        "no such jwt secret " + id + " in tenant '" + tenantId + "'"));

        entity.setIss(req.iss().trim());
        entity.setAudiencesJson(writeAudiences(req.audiences()));
        applyBinding(entity, req);
        if (req.secret() != null && !req.secret().isBlank()) {
            requireCipherConfigured();
            JwtSecretCipher.Encrypted encrypted = cipher.encrypt(req.secret().trim());
            entity.setSecretCiphertext(encrypted.ciphertext());
            entity.setSecretIv(encrypted.iv());
        }
        TenantJwtSecretEntity saved = repo.save(entity);
        events.publishEvent(new TenantJwtSecretsChangedEvent(tenantId));
        return SecretResponse.of(saved,
                req.audiences() == null ? List.of() : req.audiences(),
                parseRequiredClaims(saved.getRequiredClaimsJson()));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable("tenantId") String tenantId,
                                       @PathVariable("id") Long id,
                                       ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        TenantJwtSecretEntity entity = repo.findById(id)
                .filter(e -> e.getTenantId().equals(tenantId))
                .orElseThrow(() -> AdminApiException.notFound("jwt_secret_not_found",
                        "no such jwt secret " + id + " in tenant '" + tenantId + "'"));
        repo.delete(entity);
        events.publishEvent(new TenantJwtSecretsChangedEvent(tenantId));
        return ResponseEntity.noContent().build();
    }

    // ---- Helpers ------------------------------------------------------------

    private void requireCipherConfigured() {
        if (!cipher.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "secret cipher is not configured — set yaya.agentic.secrets.encryption-key "
                            + "(env YAYA_AGENTIC_SECRETS_ENCRYPTION_KEY) before writing jwt secrets. "
                            + "Generate one with `openssl rand -base64 32`.");
        }
    }

    private void validateCreate(SecretRequest req) {
        validateMetadata(req);
        if (req.secret() == null || req.secret().isBlank()) {
            throw AdminApiException.badRequest("missing_secret", "secret is required on create");
        }
    }

    private void validateMetadata(SecretRequest req) {
        if (req == null) throw AdminApiException.badRequest("missing_body", "request body required");
        if (req.iss() == null || req.iss().isBlank()) {
            throw AdminApiException.badRequest("missing_iss", "iss is required");
        }
        if (req.audiences() != null) {
            for (String a : req.audiences()) {
                if (a == null || a.isBlank()) {
                    throw AdminApiException.badRequest("invalid_audience",
                            "audiences must not contain blank values");
                }
            }
        }
    }

    /**
     * {@code principalKind} / {@code requiredClaims} follow "absent = keep":
     * a caller that only rotates the secret must not wipe the tenant binding.
     * A blank kind or an empty map clears. A kind may only be granted by an
     * issuer pinned to this tenant — otherwise any tenant sharing the signing
     * key could present itself as this one.
     */
    private void applyBinding(TenantJwtSecretEntity entity, SecretRequest req) {
        if (req.requiredClaims() != null) {
            for (Map.Entry<String, String> e : req.requiredClaims().entrySet()) {
                if (e.getKey() == null || e.getKey().isBlank()
                        || e.getValue() == null || e.getValue().isBlank()) {
                    throw AdminApiException.badRequest("invalid_required_claims",
                            "requiredClaims must map claim names to non-blank values");
                }
            }
            try {
                entity.setRequiredClaimsJson(req.requiredClaims().isEmpty()
                        ? null : json.writeValueAsString(new java.util.TreeMap<>(req.requiredClaims())));
            } catch (JsonProcessingException e) {
                throw AdminApiException.badRequest("invalid_required_claims",
                        "could not serialise requiredClaims: " + e.getMessage());
            }
        }
        if (req.principalKind() != null) {
            entity.setPrincipalKind(req.principalKind());
        }
        if (entity.getPrincipalKind() != null && entity.getRequiredClaimsJson() == null) {
            throw AdminApiException.badRequest("principal_kind_needs_required_claims",
                    "principalKind requires requiredClaims: pin this issuer to the tenant with at "
                            + "least one claim (e.g. {\"org\": \"<the tenant's id in the issuer>\"})");
        }
    }

    private Map<String, String> parseRequiredClaims(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            Map<String, String> map = json.readValue(raw, STRING_MAP);
            return map == null ? Map.of() : map;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String writeAudiences(List<String> audiences) {
        try {
            return json.writeValueAsString(audiences == null ? List.of() : audiences);
        } catch (JsonProcessingException e) {
            throw AdminApiException.badRequest("invalid_audiences",
                    "could not serialise audiences: " + e.getMessage());
        }
    }

    private List<String> parseAudiences(String audsJson) {
        if (audsJson == null || audsJson.isBlank()) return List.of();
        try {
            List<String> list = json.readValue(audsJson, STRING_LIST);
            return list == null ? List.of() : list;
        } catch (Exception e) {
            return List.of();
        }
    }

    // ---- DTOs ---------------------------------------------------------------

    public record SecretRequest(String iss, String secret, List<String> audiences,
                                String principalKind, Map<String, String> requiredClaims) {
        public SecretRequest(String iss, String secret, List<String> audiences) {
            this(iss, secret, audiences, null, null);
        }
    }

    /**
     * Response shape — deliberately omits the secret. Operators can rotate
     * via PUT but can't read back the value. Mirrors how Auth0 / Keycloak
     * client secrets behave.
     */
    public record SecretResponse(
            Long id,
            String tenantId,
            String iss,
            List<String> audiences,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            String principalKind,
            Map<String, String> requiredClaims
    ) {
        public static SecretResponse of(TenantJwtSecretEntity e, List<String> audiences,
                                        Map<String, String> requiredClaims) {
            return new SecretResponse(
                    e.getId(),
                    e.getTenantId(),
                    e.getIss(),
                    audiences == null ? List.of() : audiences,
                    e.getCreatedAt(),
                    e.getUpdatedAt(),
                    e.getPrincipalKind(),
                    requiredClaims == null ? Map.of() : requiredClaims);
        }
    }
}
