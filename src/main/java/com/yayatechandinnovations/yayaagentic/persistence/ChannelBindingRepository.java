package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChannelBindingRepository extends JpaRepository<ChannelBindingEntity, Long> {

    Optional<ChannelBindingEntity> findByTenantIdAndId(String tenantId, Long id);

    List<ChannelBindingEntity> findByTenantIdOrderByPriorityAscIdAsc(String tenantId);

    /** Hot path — exec on every inbound webhook. */
    List<ChannelBindingEntity> findByTenantIdAndEndpointKindAndEndpointIdAndStatusOrderByPriorityAscIdAsc(
            String tenantId, String endpointKind, String endpointId, String status);
}
