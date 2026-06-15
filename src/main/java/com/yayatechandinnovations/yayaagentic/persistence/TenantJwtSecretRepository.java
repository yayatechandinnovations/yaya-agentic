package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TenantJwtSecretRepository extends JpaRepository<TenantJwtSecretEntity, Long> {

    /** Tenant scan, called on cache miss in SharedSecretJwtAuthenticator. */
    List<TenantJwtSecretEntity> findByTenantId(String tenantId);

    Optional<TenantJwtSecretEntity> findByTenantIdAndIss(String tenantId, String iss);
}
