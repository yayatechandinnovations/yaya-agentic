package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatInboxRepository extends JpaRepository<ChatInboxEntity, Long> {

    /** Undelivered rows for a recipient, oldest first — the drain query (§11). */
    List<ChatInboxEntity> findByTenantIdAndEntryPointIdAndRecipientKeyAndDeliveredAtIsNullOrderByCreatedAtAsc(
            String tenantId, String entryPointId, String recipientKey);
}
