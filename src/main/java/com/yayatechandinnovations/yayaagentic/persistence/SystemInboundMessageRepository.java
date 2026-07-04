package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SystemInboundMessageRepository extends JpaRepository<SystemInboundMessageEntity, String> {

    Optional<SystemInboundMessageEntity> findByTenantIdAndSenderIdAndDedupKey(
            String tenantId, String senderId, String dedupKey);

    /** Integration log for the admin console (§14) — newest first, paged. */
    Page<SystemInboundMessageEntity> findByTenantIdAndSenderIdOrderByReceivedAtDesc(
            String tenantId, String senderId, Pageable pageable);
}
