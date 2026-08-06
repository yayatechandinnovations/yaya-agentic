package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WhatsAppOnboardingAttemptRepository
        extends JpaRepository<WhatsAppOnboardingAttemptEntity, String> {

    List<WhatsAppOnboardingAttemptEntity> findByTenantIdOrderByUpdatedAtDesc(String tenantId);

    Optional<WhatsAppOnboardingAttemptEntity> findByTenantIdAndId(String tenantId, String id);

    Optional<WhatsAppOnboardingAttemptEntity> findByTenantIdAndIdempotencyKey(
            String tenantId, String idempotencyKey);
}
