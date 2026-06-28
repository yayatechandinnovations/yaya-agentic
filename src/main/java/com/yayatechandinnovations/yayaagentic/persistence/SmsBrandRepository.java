package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SmsBrandRepository extends JpaRepository<SmsBrandEntity, String> {

    List<SmsBrandEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    Optional<SmsBrandEntity> findByTenantIdAndId(String tenantId, String id);

    /** v1 enforces one Brand per tenant; this backs the create-time guard. */
    List<SmsBrandEntity> findByTenantId(String tenantId);

    /** Used by the A2P poller (S1d): sweeps brands in non-terminal states across
     *  all tenants. Cross-tenant by design — the poller is platform
     *  infrastructure, not a tenant-facing query. */
    @WorkspaceUnscoped("platform A2P poller sweeps in-flight brands across workspaces")
    List<SmsBrandEntity> findByStatusIn(List<String> statuses);
}
