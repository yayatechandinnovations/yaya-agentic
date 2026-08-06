package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceAccessHelper;
import com.yayatechandinnovations.yayaagentic.persistence.IdentityProfileMappingEntity;
import com.yayatechandinnovations.yayaagentic.persistence.IdentityProfileMappingRepository;
import com.yayatechandinnovations.yayaagentic.persistence.ProfileEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ProfileRepository;
import com.yayatechandinnovations.yayaagentic.tenant.TenantGuard;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Admin CRUD for the per-tenant claim → profile mapping consumed by
 * {@code IdentityProfileResolver}. Lets an operator point yaya-agentic at
 * the right profile based on a JWT claim — "for tenant acme-dr, role=PLAYER
 * → retail-player@1" — without forking the backend.
 *
 * <p>Mapping rows are scoped to a single tenant. Path is nested under the
 * tenant on purpose so an operator switching tenants in the admin UI can't
 * accidentally cross-write.</p>
 */
@RestController
@RequestMapping("/v1/admin/tenants/{tenantId}/identity-profile-mappings")
public class IdentityProfileMappingController {

    private final TenantGuard tenantGuard;
    private final IdentityProfileMappingRepository mappings;
    private final ProfileRepository profiles;
    private final WorkspaceAccessHelper workspaceAccess;

    public IdentityProfileMappingController(TenantGuard tenantGuard,
                                            IdentityProfileMappingRepository mappings,
                                            ProfileRepository profiles,
                                            WorkspaceAccessHelper workspaceAccess) {
        this.tenantGuard = tenantGuard;
        this.mappings = mappings;
        this.profiles = profiles;
        this.workspaceAccess = workspaceAccess;
    }

    @GetMapping
    public List<MappingResponse> list(@PathVariable("tenantId") String tenantId,
                                     ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.VIEWER);
        tenantGuard.requireExists(tenantId);
        return mappings.findByTenantIdOrderByPriorityAscIdAsc(tenantId).stream()
                .map(MappingResponse::of)
                .toList();
    }

    @PostMapping
    @Transactional
    public ResponseEntity<MappingResponse> create(@PathVariable("tenantId") String tenantId,
                                                  @RequestBody MappingRequest req,
                                                  ServerWebExchange exchange) {
        // Identity mappings control which end-user routes to which profile —
        // a security-adjacent decision. ADMIN+ to write.
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        validate(req);
        requireProfileExists(tenantId, req.profileId(), req.profileVersion());

        IdentityProfileMappingEntity entity = new IdentityProfileMappingEntity(
                tenantId,
                req.claimName().trim(),
                req.claimValue().trim(),
                req.profileId().trim(),
                // Null = unpinned (latest at session start); stored as null,
                // NOT coerced to 1, so a resync that publishes v2 is served.
                req.profileVersion(),
                req.priority() == null ? 100 : req.priority());
        IdentityProfileMappingEntity saved;
        try {
            saved = mappings.save(entity);
        } catch (DataIntegrityViolationException dup) {
            throw AdminApiException.conflict("mapping_exists",
                    "a mapping for (" + req.claimName() + "=" + req.claimValue()
                            + ") already exists in tenant '" + tenantId + "'");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(MappingResponse.of(saved));
    }

    @PutMapping("/{id}")
    @Transactional
    public MappingResponse update(@PathVariable("tenantId") String tenantId,
                                  @PathVariable("id") Long id,
                                  @RequestBody MappingRequest req,
                                  ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        validate(req);
        requireProfileExists(tenantId, req.profileId(), req.profileVersion());

        IdentityProfileMappingEntity entity = mappings.findById(id)
                .filter(e -> e.getTenantId().equals(tenantId))
                .orElseThrow(() -> AdminApiException.notFound("mapping_not_found",
                        "no such mapping " + id + " in tenant '" + tenantId + "'"));

        entity.setProfileId(req.profileId().trim());
        // Null = unpinned (latest); preserved as null rather than coerced to 1.
        entity.setProfileVersion(req.profileVersion());
        entity.setPriority(req.priority() == null ? 100 : req.priority());
        return MappingResponse.of(mappings.save(entity));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable("tenantId") String tenantId,
                                       @PathVariable("id") Long id,
                                       ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        IdentityProfileMappingEntity entity = mappings.findById(id)
                .filter(e -> e.getTenantId().equals(tenantId))
                .orElseThrow(() -> AdminApiException.notFound("mapping_not_found",
                        "no such mapping " + id + " in tenant '" + tenantId + "'"));
        mappings.delete(entity);
        return ResponseEntity.noContent().build();
    }

    // ---- Helpers ------------------------------------------------------------

    private void validate(MappingRequest req) {
        if (req == null) throw AdminApiException.badRequest("missing_body", "request body required");
        if (req.claimName() == null || req.claimName().isBlank()) {
            throw AdminApiException.badRequest("missing_claim_name", "claimName is required");
        }
        if (req.claimValue() == null || req.claimValue().isBlank()) {
            throw AdminApiException.badRequest("missing_claim_value", "claimValue is required");
        }
        if (req.profileId() == null || req.profileId().isBlank()) {
            throw AdminApiException.badRequest("missing_profile_id", "profileId is required");
        }
        if (req.profileVersion() != null && req.profileVersion() < 1) {
            throw AdminApiException.badRequest("invalid_profile_version",
                    "profileVersion must be >= 1");
        }
    }

    private void requireProfileExists(String tenantId, String profileId, Integer profileVersion) {
        // Unpinned (null) mapping: any published version of the id is enough —
        // the concrete "latest" is picked at session start, not pinned here.
        if (profileVersion == null) {
            boolean anyVersion = !profiles
                    .findByTenantIdAndIdOrderByVersionDesc(tenantId, profileId).isEmpty();
            if (!anyVersion) {
                throw AdminApiException.badRequest("unknown_profile",
                        "no such profile '" + profileId + "' in tenant '" + tenantId + "'");
            }
            return;
        }
        profiles.findById(new ProfileEntity.PK(tenantId, profileId, profileVersion))
                .orElseThrow(() -> AdminApiException.badRequest("unknown_profile",
                        "no such profile '" + profileId + "@" + profileVersion + "' in tenant '" + tenantId + "'"));
    }

    // ---- DTOs ---------------------------------------------------------------

    public record MappingRequest(
            String claimName,
            String claimValue,
            String profileId,
            Integer profileVersion,
            Integer priority
    ) {}

    public record MappingResponse(
            Long id,
            String tenantId,
            String claimName,
            String claimValue,
            String profileId,
            Integer profileVersion,
            int priority,
            OffsetDateTime createdAt
    ) {
        public static MappingResponse of(IdentityProfileMappingEntity e) {
            return new MappingResponse(
                    e.getId(),
                    e.getTenantId(),
                    e.getClaimName(),
                    e.getClaimValue(),
                    e.getProfileId(),
                    e.getProfileVersion(),
                    e.getPriority(),
                    e.getCreatedAt());
        }
    }
}
