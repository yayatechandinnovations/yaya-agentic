package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatEntryPointRepository extends JpaRepository<ChatEntryPointEntity, String> {

    List<ChatEntryPointEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    Optional<ChatEntryPointEntity> findByTenantIdAndId(String tenantId, String id);

    /** Public front-door lookup. The handle is the tenant-agnostic capability in
     *  the QR / deep link, so resolution must cross workspaces — the entry point
     *  carries its own tenant. */
    @WorkspaceUnscoped("handle is the globally-unique public capability; the front door has no tenant context yet")
    Optional<ChatEntryPointEntity> findByHandle(String handle);
}
