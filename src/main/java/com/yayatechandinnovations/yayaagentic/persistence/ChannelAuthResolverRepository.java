package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChannelAuthResolverRepository
        extends JpaRepository<ChannelAuthResolverEntity, Long> {

    Optional<ChannelAuthResolverEntity> findByTenantIdAndName(String tenantId, String name);

    List<ChannelAuthResolverEntity> findByTenantIdOrderByNameAsc(String tenantId);

    List<ChannelAuthResolverEntity> findByTenantIdAndStatusOrderByNameAsc(
            String tenantId, String status);
}
