package com.yayatechandinnovations.yayaagentic.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.auth.jwt.TenantJwtSecretsChangedEvent;
import com.yayatechandinnovations.yayaagentic.auth.secrets.SecretCipher;
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

import java.time.OffsetDateTime;
import java.util.List;

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

    private final TenantGuard tenantGuard;
    private final TenantJwtSecretRepository repo;
    private final SecretCipher cipher;
    private final ObjectMapper json;
    private final ApplicationEventPublisher events;

    public TenantJwtSecretController(TenantGuard tenantGuard,
                                     TenantJwtSecretRepository repo,
                                     SecretCipher cipher,
                                     ObjectMapper json,
                                     ApplicationEventPublisher events) {
        this.tenantGuard = tenantGuard;
        this.repo = repo;
        this.cipher = cipher;
        this.json = json;
        this.events = events;
    }

    @GetMapping
    public List<SecretResponse> list(@PathVariable("tenantId") String tenantId) {
        tenantGuard.requireExists(tenantId);
        return repo.findByTenantId(tenantId).stream()
                .map(e -> SecretResponse.of(e, parseAudiences(e.getAudiencesJson())))
                .toList();
    }

    @PostMapping
    @Transactional
    public ResponseEntity<SecretResponse> create(@PathVariable("tenantId") String tenantId,
                                                 @RequestBody SecretRequest req) {
        tenantGuard.requireWritable(tenantId);
        requireCipherConfigured();
        validateCreate(req);

        SecretCipher.Encrypted encrypted = cipher.encrypt(req.secret().trim());
        TenantJwtSecretEntity entity = new TenantJwtSecretEntity(
                tenantId, req.iss().trim(),
                encrypted.ciphertext(), encrypted.iv(),
                writeAudiences(req.audiences()));
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
                        req.audiences() == null ? List.of() : req.audiences()));
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
                                 @RequestBody SecretRequest req) {
        tenantGuard.requireWritable(tenantId);
        validateMetadata(req);

        TenantJwtSecretEntity entity = repo.findById(id)
                .filter(e -> e.getTenantId().equals(tenantId))
                .orElseThrow(() -> AdminApiException.notFound("jwt_secret_not_found",
                        "no such jwt secret " + id + " in tenant '" + tenantId + "'"));

        entity.setIss(req.iss().trim());
        entity.setAudiencesJson(writeAudiences(req.audiences()));
        if (req.secret() != null && !req.secret().isBlank()) {
            requireCipherConfigured();
            SecretCipher.Encrypted encrypted = cipher.encrypt(req.secret().trim());
            entity.setSecretCiphertext(encrypted.ciphertext());
            entity.setSecretIv(encrypted.iv());
        }
        TenantJwtSecretEntity saved = repo.save(entity);
        events.publishEvent(new TenantJwtSecretsChangedEvent(tenantId));
        return SecretResponse.of(saved,
                req.audiences() == null ? List.of() : req.audiences());
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable("tenantId") String tenantId,
                                       @PathVariable("id") Long id) {
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

    public record SecretRequest(String iss, String secret, List<String> audiences) {}

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
            OffsetDateTime updatedAt
    ) {
        public static SecretResponse of(TenantJwtSecretEntity e, List<String> audiences) {
            return new SecretResponse(
                    e.getId(),
                    e.getTenantId(),
                    e.getIss(),
                    audiences == null ? List.of() : audiences,
                    e.getCreatedAt(),
                    e.getUpdatedAt());
        }
    }
}
