package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RecordingStrategyRepository extends JpaRepository<RecordingStrategyEntity, Long> {

    List<RecordingStrategyEntity> findByTenantIdAndScopeKindAndScopeIdOrderByVersionDesc(
            String tenantId, String scopeKind, String scopeId);

    default Optional<RecordingStrategyEntity> findLatest(String tenantId, String scopeKind, String scopeId) {
        return findByTenantIdAndScopeKindAndScopeIdOrderByVersionDesc(tenantId, scopeKind, scopeId)
                .stream().findFirst();
    }
}
