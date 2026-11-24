package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WhatsAppSharedRouteRepository
        extends JpaRepository<WhatsAppSharedRouteEntity, WhatsAppSharedRouteEntity.PK> {

    /** Ending an assignment unpins every phone pinned to that workspace (§11). */
    @Modifying
    @Query("UPDATE WhatsAppSharedRouteEntity r SET r.activeTenantId = null, r.activeAt = null "
            + "WHERE r.senderId = :senderId AND r.activeTenantId = :tenantId")
    int clearActive(@Param("senderId") String senderId, @Param("tenantId") String tenantId);

    @WorkspaceUnscoped("route rows are keyed by sender and phone; the workspace is their value")
    default java.util.Optional<WhatsAppSharedRouteEntity> find(String senderId, String phone) {
        return findById(new WhatsAppSharedRouteEntity.PK(senderId, phone));
    }
}
