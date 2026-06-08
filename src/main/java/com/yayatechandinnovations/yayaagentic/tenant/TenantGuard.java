package com.yayatechandinnovations.yayaagentic.tenant;

import com.yayatechandinnovations.yayaagentic.api.AdminApiException;
import com.yayatechandinnovations.yayaagentic.persistence.TenantEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantRepository;
import org.springframework.stereotype.Component;

/**
 * Single enforcement point for "is this tenant a real, addressable target?"
 * Replaces the implicit {@code ensureTenant} auto-create that lived in the
 * admin controllers — design §4: writes against unknown tenants fail loud
 * with {@code unknown_tenant} rather than silently materializing rows.
 */
@Component
public class TenantGuard {

    private final TenantRepository tenants;

    public TenantGuard(TenantRepository tenants) {
        this.tenants = tenants;
    }

    /** Admin-write entry point. Rejects unknown and archived tenants. */
    public TenantEntity requireWritable(String tenantId) {
        TenantEntity entity = requireExists(tenantId);
        if (TenantEntity.Status.ARCHIVED.name().equals(entity.getStatus())) {
            throw AdminApiException.conflict("tenant_archived",
                    "tenant '" + tenantId + "' is archived; writes are not accepted");
        }
        return entity;
    }

    /** Session/dispatch entry point. Rejects unknown, suspended, and archived. */
    public TenantEntity requireActive(String tenantId) {
        TenantEntity entity = requireExists(tenantId);
        if (!TenantEntity.Status.ACTIVE.name().equals(entity.getStatus())) {
            throw AdminApiException.forbidden("tenant_not_active",
                    "tenant '" + tenantId + "' is " + entity.getStatus()
                            + "; new sessions are not accepted");
        }
        return entity;
    }

    public TenantEntity requireExists(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw AdminApiException.badRequest("unknown_tenant", "tenant id is required");
        }
        return tenants.findById(tenantId).orElseThrow(() ->
                AdminApiException.badRequest("unknown_tenant",
                        "no such tenant: " + tenantId));
    }
}
