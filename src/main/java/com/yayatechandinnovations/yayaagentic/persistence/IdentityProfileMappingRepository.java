package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IdentityProfileMappingRepository extends JpaRepository<IdentityProfileMappingEntity, Long> {

    /** Tenant-scoped ordered scan. Resolver walks this in order, first match wins. */
    List<IdentityProfileMappingEntity> findByTenantIdOrderByPriorityAscIdAsc(String tenantId);

    Optional<IdentityProfileMappingEntity> findByTenantIdAndClaimNameAndClaimValue(
            String tenantId, String claimName, String claimValue);
}
