package com.yayatechandinnovations.yayaagentic.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.auth.oidc.TenantOidcIssuersChangedEvent;
import com.yayatechandinnovations.yayaagentic.persistence.TenantOidcIssuerEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantOidcIssuerRepository;
import com.yayatechandinnovations.yayaagentic.tenant.TenantGuard;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Admin CRUD for the per-tenant OIDC issuer trust list read by
 * {@code OidcAuthenticator}. Lets each tenant declare its IdP at runtime
 * — no backend restart, no shared YAML. Every write publishes a
 * {@link TenantOidcIssuersChangedEvent} so the authenticator's in-memory
 * processor cache is evicted before the next auth attempt.
 *
 * <p>Path is nested under the tenant so an operator switching tenants in
 * the admin UI can't accidentally cross-write.</p>
 */
@RestController
@RequestMapping("/v1/admin/tenants/{tenantId}/oidc-issuers")
public class TenantOidcIssuerController {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final TenantGuard tenantGuard;
    private final TenantOidcIssuerRepository repo;
    private final ObjectMapper json;
    private final ApplicationEventPublisher events;

    public TenantOidcIssuerController(TenantGuard tenantGuard,
                                      TenantOidcIssuerRepository repo,
                                      ObjectMapper json,
                                      ApplicationEventPublisher events) {
        this.tenantGuard = tenantGuard;
        this.repo = repo;
        this.json = json;
        this.events = events;
    }

    @GetMapping
    public List<IssuerResponse> list(@PathVariable("tenantId") String tenantId) {
        tenantGuard.requireExists(tenantId);
        return repo.findByTenantId(tenantId).stream()
                .map(e -> IssuerResponse.of(e, parseAudiences(e.getAudiencesJson())))
                .toList();
    }

    @PostMapping
    @Transactional
    public ResponseEntity<IssuerResponse> create(@PathVariable("tenantId") String tenantId,
                                                 @RequestBody IssuerRequest req) {
        tenantGuard.requireWritable(tenantId);
        validate(req);

        TenantOidcIssuerEntity entity = new TenantOidcIssuerEntity(
                tenantId, req.iss().trim(), req.jwksUri().trim(), writeAudiences(req.audiences()));
        TenantOidcIssuerEntity saved;
        try {
            saved = repo.save(entity);
        } catch (DataIntegrityViolationException dup) {
            throw AdminApiException.conflict("issuer_exists",
                    "an issuer '" + req.iss() + "' is already registered for tenant '" + tenantId + "'");
        }
        events.publishEvent(new TenantOidcIssuersChangedEvent(tenantId));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(IssuerResponse.of(saved, req.audiences() == null ? List.of() : req.audiences()));
    }

    @PutMapping("/{id}")
    @Transactional
    public IssuerResponse update(@PathVariable("tenantId") String tenantId,
                                 @PathVariable("id") Long id,
                                 @RequestBody IssuerRequest req) {
        tenantGuard.requireWritable(tenantId);
        validate(req);

        TenantOidcIssuerEntity entity = repo.findById(id)
                .filter(e -> e.getTenantId().equals(tenantId))
                .orElseThrow(() -> AdminApiException.notFound("issuer_not_found",
                        "no such oidc issuer " + id + " in tenant '" + tenantId + "'"));

        entity.setIss(req.iss().trim());
        entity.setJwksUri(req.jwksUri().trim());
        entity.setAudiencesJson(writeAudiences(req.audiences()));
        TenantOidcIssuerEntity saved = repo.save(entity);
        events.publishEvent(new TenantOidcIssuersChangedEvent(tenantId));
        return IssuerResponse.of(saved, req.audiences() == null ? List.of() : req.audiences());
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable("tenantId") String tenantId,
                                       @PathVariable("id") Long id) {
        tenantGuard.requireWritable(tenantId);
        TenantOidcIssuerEntity entity = repo.findById(id)
                .filter(e -> e.getTenantId().equals(tenantId))
                .orElseThrow(() -> AdminApiException.notFound("issuer_not_found",
                        "no such oidc issuer " + id + " in tenant '" + tenantId + "'"));
        repo.delete(entity);
        events.publishEvent(new TenantOidcIssuersChangedEvent(tenantId));
        return ResponseEntity.noContent().build();
    }

    // ---- Helpers ------------------------------------------------------------

    private void validate(IssuerRequest req) {
        if (req == null) throw AdminApiException.badRequest("missing_body", "request body required");
        if (req.iss() == null || req.iss().isBlank()) {
            throw AdminApiException.badRequest("missing_iss", "iss is required");
        }
        if (req.jwksUri() == null || req.jwksUri().isBlank()) {
            throw AdminApiException.badRequest("missing_jwks_uri", "jwksUri is required");
        }
        requireUrl(req.iss().trim(), "iss");
        requireUrl(req.jwksUri().trim(), "jwksUri");
        if (req.audiences() != null) {
            for (String a : req.audiences()) {
                if (a == null || a.isBlank()) {
                    throw AdminApiException.badRequest("invalid_audience",
                            "audiences must not contain blank values");
                }
            }
        }
    }

    private static void requireUrl(String value, String field) {
        try {
            URI uri = URI.create(value);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException("missing scheme or host");
            }
        } catch (Exception e) {
            throw AdminApiException.badRequest("invalid_url",
                    field + " must be an absolute URL (got '" + value + "')");
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

    public record IssuerRequest(String iss, String jwksUri, List<String> audiences) {}

    public record IssuerResponse(
            Long id,
            String tenantId,
            String iss,
            String jwksUri,
            List<String> audiences,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        public static IssuerResponse of(TenantOidcIssuerEntity e, List<String> audiences) {
            return new IssuerResponse(
                    e.getId(),
                    e.getTenantId(),
                    e.getIss(),
                    e.getJwksUri(),
                    audiences == null ? List.of() : audiences,
                    e.getCreatedAt(),
                    e.getUpdatedAt());
        }
    }
}
