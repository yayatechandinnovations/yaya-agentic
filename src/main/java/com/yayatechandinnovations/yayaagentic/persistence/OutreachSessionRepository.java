package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OutreachSessionRepository extends JpaRepository<OutreachSessionEntity, Long> {

    Optional<OutreachSessionEntity> findByTenantIdAndEntryPointIdAndRecipientKey(
            String tenantId, String entryPointId, String recipientKey);
}
