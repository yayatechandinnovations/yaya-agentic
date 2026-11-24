package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WhatsAppSenderAssignmentRepository extends JpaRepository<WhatsAppSenderAssignmentEntity, String> {

    @WorkspaceUnscoped("a platform sender's assignments span workspaces; platform-admin surface and the router")
    List<WhatsAppSenderAssignmentEntity> findBySenderIdAndStatusNotOrderByAssignedAtAsc(String senderId, String status);

    @WorkspaceUnscoped("a platform sender's assignments span workspaces; platform-admin surface and the router")
    default List<WhatsAppSenderAssignmentEntity> findLiveBySender(String senderId) {
        return findBySenderIdAndStatusNotOrderByAssignedAtAsc(senderId, WhatsAppSenderAssignmentEntity.ENDED);
    }

    Optional<WhatsAppSenderAssignmentEntity> findFirstBySenderIdAndTenantIdAndStatusNot(
            String senderId, String tenantId, String status);

    default Optional<WhatsAppSenderAssignmentEntity> findLive(String senderId, String tenantId) {
        return findFirstBySenderIdAndTenantIdAndStatusNot(senderId, tenantId, WhatsAppSenderAssignmentEntity.ENDED);
    }

    List<WhatsAppSenderAssignmentEntity> findByTenantIdAndStatusNot(String tenantId, String status);

    default List<WhatsAppSenderAssignmentEntity> findLiveByTenant(String tenantId) {
        return findByTenantIdAndStatusNot(tenantId, WhatsAppSenderAssignmentEntity.ENDED);
    }
}
