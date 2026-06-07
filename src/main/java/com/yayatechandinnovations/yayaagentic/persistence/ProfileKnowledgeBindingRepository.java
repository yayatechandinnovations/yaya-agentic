package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProfileKnowledgeBindingRepository
        extends JpaRepository<ProfileKnowledgeBindingEntity, ProfileKnowledgeBindingEntity.PK> {

    List<ProfileKnowledgeBindingEntity> findByTenantIdAndProfileIdAndProfileVersion(
            String tenantId, String profileId, Integer profileVersion);

    void deleteByTenantIdAndProfileIdAndProfileVersion(
            String tenantId, String profileId, Integer profileVersion);
}
