package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OutboundMessageRepository extends JpaRepository<OutboundMessageEntity, String> {

    Optional<OutboundMessageEntity> findByTenantIdAndMessageSid(String tenantId, String messageSid);

    List<OutboundMessageEntity> findByTenantIdAndSenderIdAndEndUserPhoneOrderBySentAtDesc(
            String tenantId, String senderId, String endUserPhone);

    /** Retry job: pick queued rows ready for re-dispatch. Cross-tenant by
     *  design — the retry job is platform infrastructure. */
    @WorkspaceUnscoped("retry sweeper runs across workspaces")
    List<OutboundMessageEntity> findTop50ByStatusAndRetryCountLessThanOrderBySentAtAsc(
            String status, int maxRetryCount);
}
