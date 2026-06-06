package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProfileRepository extends JpaRepository<ProfileEntity, ProfileEntity.PK> {
    List<ProfileEntity> findByTenantId(String tenantId);
    List<ProfileEntity> findByTenantIdAndIdOrderByVersionDesc(String tenantId, String id);
}
