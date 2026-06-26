package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboundPendingRepository extends JpaRepository<OutboundPendingEntity, Long> {

    /** §10.2: release queued messages on the next inbound from the same
     *  (sender, end_user). Ordered by queued_at so they emerge in original
     *  order. */
    List<OutboundPendingEntity> findByTenantIdAndSenderIdAndEndUserPhoneAndReleasedAtIsNullOrderByQueuedAtAsc(
            String tenantId, String senderId, String endUserPhone);
}
