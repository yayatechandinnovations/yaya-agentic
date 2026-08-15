package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TenantNetworkSettingsRepository
        extends JpaRepository<TenantNetworkSettingsEntity, String> {

    Optional<TenantNetworkSettingsEntity> findByTenantId(String tenantId);
}
