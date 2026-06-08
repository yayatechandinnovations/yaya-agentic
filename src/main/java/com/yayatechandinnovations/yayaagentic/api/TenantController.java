package com.yayatechandinnovations.yayaagentic.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.api.dto.AdminDtos;
import com.yayatechandinnovations.yayaagentic.persistence.AuthBindingEntity;
import com.yayatechandinnovations.yayaagentic.persistence.AuthBindingRepository;
import com.yayatechandinnovations.yayaagentic.persistence.RecordingStrategyEntity;
import com.yayatechandinnovations.yayaagentic.persistence.RecordingStrategyRepository;
import com.yayatechandinnovations.yayaagentic.persistence.TenantEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantRepository;
import com.yayatechandinnovations.yayaagentic.tenant.BaseUrlValidator;
import com.yayatechandinnovations.yayaagentic.tenant.TenantSlug;
import com.yayatechandinnovations.yayaagentic.tenant.clone.CloneRequest;
import com.yayatechandinnovations.yayaagentic.tenant.clone.CloneResult;
import com.yayatechandinnovations.yayaagentic.tenant.clone.CloneService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tenant CRUD + lifecycle per docs/design/tenant-registry-design.md §3, §4.
 *
 * <p>Tenants are created exclusively through this controller; admin writes
 * elsewhere fail with {@code unknown_tenant} rather than auto-creating
 * (the old {@code ensureTenant} silent path is removed).</p>
 */
@RestController
@RequestMapping("/v1/admin/tenants")
public class TenantController {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP_REF = new TypeReference<>() {};

    private final TenantRepository tenants;
    private final AuthBindingRepository authBindings;
    private final RecordingStrategyRepository recordingStrategies;
    private final ObjectMapper json;
    private final CloneService cloneService;

    public TenantController(TenantRepository tenants,
                            AuthBindingRepository authBindings,
                            RecordingStrategyRepository recordingStrategies,
                            ObjectMapper json,
                            CloneService cloneService) {
        this.tenants = tenants;
        this.authBindings = authBindings;
        this.recordingStrategies = recordingStrategies;
        this.json = json;
        this.cloneService = cloneService;
    }

    @GetMapping
    public List<AdminDtos.TenantResponse> list(@RequestParam(value = "status", required = false) String status) {
        List<TenantEntity> rows = (status == null || status.isBlank())
                ? tenants.findAll()
                : tenants.findAllByStatus(status.toUpperCase());
        return rows.stream().map(this::toResponse).toList();
    }

    @GetMapping("/{id}")
    public AdminDtos.TenantResponse get(@PathVariable("id") String id) {
        return toResponse(loadOrThrow(id));
    }

    @PostMapping
    @Transactional
    public ResponseEntity<AdminDtos.TenantResponse> create(@RequestBody AdminDtos.TenantRequest req) {
        String id = req.id();
        TenantSlug.validate(id);
        if (tenants.existsById(id)) {
            TenantEntity existing = tenants.findById(id).orElseThrow();
            if (TenantEntity.Status.ARCHIVED.name().equals(existing.getStatus())) {
                throw AdminApiException.conflict("tenant_archived",
                        "tenant '" + id + "' was archived and cannot be recreated under the same id");
            }
            throw AdminApiException.conflict("tenant_id_taken",
                    "tenant id '" + id + "' is already taken");
        }

        if (req.displayName() == null || req.displayName().isBlank()) {
            throw AdminApiException.badRequest("missing_display_name",
                    "displayName is required");
        }
        boolean requireHttps = req.requireHttps() == null ? true : req.requireHttps();
        BaseUrlValidator.validateHostBaseUrl(req.hostBaseUrl(), requireHttps);
        validateHostBaseAllowlist(req.hostBaseUrlAllowlist(), requireHttps);
        validateInboundOriginAllowlist(req.inboundOriginAllowlist(), requireHttps);

        TenantEntity entity = new TenantEntity(id, req.displayName());
        entity.setStatus(TenantEntity.Status.ACTIVE);
        entity.setHostBaseUrl(req.hostBaseUrl());
        entity.setHostBaseUrlAllowlistJson(writeStringList(req.hostBaseUrlAllowlist()));
        entity.setInboundOriginAllowlistJson(writeStringList(req.inboundOriginAllowlist()));
        entity.setRequireHttps(requireHttps);
        if (req.defaultAuthenticatorBindingId() != null && !req.defaultAuthenticatorBindingId().isBlank()) {
            requireAuthBindingExists(id, req.defaultAuthenticatorBindingId());
            entity.setDefaultAuthenticatorBindingId(req.defaultAuthenticatorBindingId());
        }
        if (req.defaultRecordingStrategyId() != null) {
            requireRecordingStrategyTenantScoped(id, req.defaultRecordingStrategyId());
            entity.setDefaultRecordingStrategyId(req.defaultRecordingStrategyId());
        }
        entity.setSettingsJson(writeJson(req.settings() == null ? Map.of() : req.settings()));
        // created_by lands here when M5 operator-auth wires a principal accessor in;
        // for now the column stays null and the migration test reflects that.

        tenants.save(entity);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(entity));
    }

    @PutMapping("/{id}")
    @Transactional
    public AdminDtos.TenantResponse replace(@PathVariable("id") String id,
                                            @RequestBody AdminDtos.TenantRequest req) {
        TenantEntity entity = loadOrThrow(id);
        rejectIfArchived(entity, "tenant is archived; reads only");
        applyMutations(entity, req, true);
        tenants.save(entity);
        return toResponse(entity);
    }

    @PatchMapping("/{id}")
    @Transactional
    public AdminDtos.TenantResponse patch(@PathVariable("id") String id,
                                          @RequestBody AdminDtos.TenantRequest req) {
        TenantEntity entity = loadOrThrow(id);
        rejectIfArchived(entity, "tenant is archived; reads only");
        applyMutations(entity, req, false);
        tenants.save(entity);
        return toResponse(entity);
    }

    @PostMapping("/{id}/suspend")
    @Transactional
    public AdminDtos.TenantResponse suspend(@PathVariable("id") String id) {
        TenantEntity entity = loadOrThrow(id);
        rejectIfArchived(entity, "archived tenants cannot be suspended");
        entity.setStatus(TenantEntity.Status.SUSPENDED);
        tenants.save(entity);
        return toResponse(entity);
    }

    @PostMapping("/{id}/resume")
    @Transactional
    public AdminDtos.TenantResponse resume(@PathVariable("id") String id) {
        TenantEntity entity = loadOrThrow(id);
        rejectIfArchived(entity, "archived tenants cannot be resumed");
        entity.setStatus(TenantEntity.Status.ACTIVE);
        tenants.save(entity);
        return toResponse(entity);
    }

    @PostMapping("/{id}/archive")
    @Transactional
    public AdminDtos.TenantResponse archive(@PathVariable("id") String id) {
        TenantEntity entity = loadOrThrow(id);
        if (TenantEntity.Status.ARCHIVED.name().equals(entity.getStatus())) {
            return toResponse(entity);
        }
        entity.setStatus(TenantEntity.Status.ARCHIVED);
        entity.setArchivedAt(java.time.OffsetDateTime.now());
        tenants.save(entity);
        return toResponse(entity);
    }

    @GetMapping("/{id}/health")
    public AdminDtos.TenantHealthResponse health(@PathVariable("id") String id) {
        TenantEntity entity = loadOrThrow(id);
        List<String> warnings = new ArrayList<>();

        boolean hostSet = entity.getHostBaseUrl() != null && !entity.getHostBaseUrl().isBlank();
        if (!hostSet && TenantEntity.Status.ACTIVE.name().equals(entity.getStatus())) {
            warnings.add("hostBaseUrl is not set; HTTP tool dispatch will refuse to fire");
        }

        boolean authResolves = false;
        if (entity.getDefaultAuthenticatorBindingId() != null) {
            authResolves = authBindings.existsById(new AuthBindingEntity.PK(
                    entity.getId(), entity.getDefaultAuthenticatorBindingId()));
            if (!authResolves) {
                warnings.add("defaultAuthenticatorBindingId references a missing binding: "
                        + entity.getDefaultAuthenticatorBindingId());
            }
        } else {
            warnings.add("defaultAuthenticatorBindingId is not set");
        }

        boolean recordingResolves = true;
        if (entity.getDefaultRecordingStrategyId() != null) {
            Optional<RecordingStrategyEntity> rs = recordingStrategies
                    .findById(entity.getDefaultRecordingStrategyId());
            recordingResolves = rs.isPresent() && entity.getId().equals(rs.get().getTenantId());
            if (!recordingResolves) {
                warnings.add("defaultRecordingStrategyId does not resolve to a TENANT-scoped row for this tenant");
            }
        }

        int dependencyCount = (entity.getDefaultAuthenticatorBindingId() == null ? 0 : 1)
                + (entity.getDefaultRecordingStrategyId() == null ? 0 : 1);

        return new AdminDtos.TenantHealthResponse(
                entity.getId(), entity.getStatus(),
                hostSet, authResolves, recordingResolves, dependencyCount, warnings);
    }

    // ---- Cross-tenant profile clone (§7) -------------------------------

    @PostMapping("/{src}/profiles/{profileId}/{version}/clone")
    public CloneResult cloneProfile(@PathVariable("src") String sourceTenant,
                                    @PathVariable("profileId") String profileId,
                                    @PathVariable("version") int version,
                                    @RequestBody CloneRequest body) {
        if (body == null || body.destinationTenant() == null || body.destinationTenant().isBlank()) {
            throw AdminApiException.badRequest("missing_destination_tenant",
                    "destinationTenant is required");
        }
        // Operator-identity wiring lands when M5 operator-auth surfaces it
        // through a request-scoped accessor; until then applied_by stays null.
        return cloneService.cloneProfile(sourceTenant, profileId, version, body, null);
    }

    // ---- helpers --------------------------------------------------------

    private void applyMutations(TenantEntity entity, AdminDtos.TenantRequest req, boolean replace) {
        // Determine effective requireHttps first; URL/allowlist validation depends on it.
        boolean effectiveRequireHttps = (req.requireHttps() != null)
                ? req.requireHttps()
                : (replace ? true : entity.isRequireHttps());

        if (replace || req.displayName() != null) {
            if (req.displayName() == null || req.displayName().isBlank()) {
                throw AdminApiException.badRequest("missing_display_name",
                        "displayName is required");
            }
            entity.setDisplayName(req.displayName());
        }
        if (replace || req.hostBaseUrl() != null) {
            BaseUrlValidator.validateHostBaseUrl(req.hostBaseUrl(), effectiveRequireHttps);
            entity.setHostBaseUrl(req.hostBaseUrl());
        }
        if (replace || req.hostBaseUrlAllowlist() != null) {
            List<String> list = req.hostBaseUrlAllowlist() == null ? List.of() : req.hostBaseUrlAllowlist();
            validateHostBaseAllowlist(list, effectiveRequireHttps);
            entity.setHostBaseUrlAllowlistJson(writeStringList(list));
        }
        if (replace || req.inboundOriginAllowlist() != null) {
            List<String> list = req.inboundOriginAllowlist() == null ? List.of() : req.inboundOriginAllowlist();
            validateInboundOriginAllowlist(list, effectiveRequireHttps);
            entity.setInboundOriginAllowlistJson(writeStringList(list));
        }
        if (req.requireHttps() != null) {
            entity.setRequireHttps(req.requireHttps());
        } else if (replace) {
            entity.setRequireHttps(true);
        }
        if (replace || req.defaultAuthenticatorBindingId() != null) {
            String bindingId = req.defaultAuthenticatorBindingId();
            if (bindingId != null && !bindingId.isBlank()) {
                requireAuthBindingExists(entity.getId(), bindingId);
                entity.setDefaultAuthenticatorBindingId(bindingId);
            } else if (replace) {
                entity.setDefaultAuthenticatorBindingId(null);
            }
        }
        if (replace || req.defaultRecordingStrategyId() != null) {
            Long rsId = req.defaultRecordingStrategyId();
            if (rsId != null) {
                requireRecordingStrategyTenantScoped(entity.getId(), rsId);
                entity.setDefaultRecordingStrategyId(rsId);
            } else if (replace) {
                entity.setDefaultRecordingStrategyId(null);
            }
        }
        if (replace || req.settings() != null) {
            entity.setSettingsJson(writeJson(req.settings() == null ? Map.of() : req.settings()));
        }
    }

    private void validateHostBaseAllowlist(List<String> list, boolean requireHttps) {
        if (list == null) return;
        for (String entry : list) {
            BaseUrlValidator.validateUrlPattern(entry, requireHttps);
        }
    }

    private void validateInboundOriginAllowlist(List<String> list, boolean requireHttps) {
        if (list == null) return;
        for (String entry : list) {
            BaseUrlValidator.validateOriginPattern(entry, requireHttps);
        }
    }

    private void requireAuthBindingExists(String tenantId, String bindingId) {
        if (!authBindings.existsById(new AuthBindingEntity.PK(tenantId, bindingId))) {
            throw AdminApiException.badRequest("unknown_auth_binding",
                    "defaultAuthenticatorBindingId '" + bindingId + "' is not registered for tenant " + tenantId);
        }
    }

    private void requireRecordingStrategyTenantScoped(String tenantId, long strategyId) {
        Optional<RecordingStrategyEntity> rs = recordingStrategies.findById(strategyId);
        if (rs.isEmpty()) {
            throw AdminApiException.badRequest("unknown_recording_strategy",
                    "defaultRecordingStrategyId " + strategyId + " does not exist");
        }
        RecordingStrategyEntity row = rs.get();
        if (!tenantId.equals(row.getTenantId())) {
            throw AdminApiException.badRequest("recording_strategy_wrong_tenant",
                    "defaultRecordingStrategyId " + strategyId
                            + " belongs to a different tenant");
        }
        if (!"TENANT".equals(row.getScopeKind())) {
            throw AdminApiException.badRequest("recording_strategy_wrong_scope",
                    "defaultRecordingStrategyId " + strategyId
                            + " must be scoped TENANT (got " + row.getScopeKind() + ")");
        }
    }

    private TenantEntity loadOrThrow(String id) {
        return tenants.findById(id).orElseThrow(() ->
                AdminApiException.notFound("unknown_tenant", "no such tenant: " + id));
    }

    private void rejectIfArchived(TenantEntity entity, String message) {
        if (TenantEntity.Status.ARCHIVED.name().equals(entity.getStatus())) {
            throw AdminApiException.conflict("tenant_archived", message);
        }
    }

    private AdminDtos.TenantResponse toResponse(TenantEntity e) {
        return new AdminDtos.TenantResponse(
                e.getId(), e.getDisplayName(), e.getStatus(), e.getHostBaseUrl(),
                readStringList(e.getHostBaseUrlAllowlistJson()),
                readStringList(e.getInboundOriginAllowlistJson()),
                e.isRequireHttps(),
                e.getDefaultAuthenticatorBindingId(),
                e.getDefaultRecordingStrategyId(),
                readMap(e.getSettingsJson()),
                e.getCreatedAt(), e.getUpdatedAt(), e.getArchivedAt(),
                e.getCreatedBy());
    }

    private String writeStringList(List<String> list) {
        return writeJson(list == null ? List.of() : list);
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("tenants: failed to encode JSON", e);
        }
    }

    private List<String> readStringList(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            return json.readValue(raw, STRING_LIST);
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> readMap(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            return json.readValue(raw, MAP_REF);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
