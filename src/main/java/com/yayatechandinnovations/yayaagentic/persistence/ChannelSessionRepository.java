package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChannelSessionRepository extends JpaRepository<ChannelSessionEntity, Long> {

    Optional<ChannelSessionEntity> findFirstByTenantIdAndSenderIdAndEndUserPhoneAndStatus(
            String tenantId, String senderId, String endUserPhone, String status);

    Optional<ChannelSessionEntity> findByTenantIdAndId(String tenantId, Long id);

    Optional<ChannelSessionEntity> findByTenantIdAndSessionId(String tenantId, String sessionId);

    List<ChannelSessionEntity> findByTenantIdAndSenderIdAndStatus(
            String tenantId, String senderId, String status);

    /** Used by the operator admin "live channel sessions" view (later W). */
    List<ChannelSessionEntity> findByTenantIdAndStatusOrderByLastInboundAtDesc(
            String tenantId, String status);

    /** Cross-tenant: the per-tenant view above is preferred; this is for an
     *  operator-tier dashboard that needs to see all senders in flight. */
    @WorkspaceUnscoped("operator-tier overview of all open WhatsApp sessions")
    List<ChannelSessionEntity> findByStatusOrderByLastInboundAtDesc(String status);
}
