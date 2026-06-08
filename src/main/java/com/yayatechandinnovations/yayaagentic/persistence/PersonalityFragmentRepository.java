package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PersonalityFragmentRepository
        extends JpaRepository<PersonalityFragmentEntity, Long> {

    List<PersonalityFragmentEntity> findByTenantId(String tenantId);

    List<PersonalityFragmentEntity> findByTenantIdAndLocaleOrderByVersionDesc(String tenantId, String locale);

    default Optional<PersonalityFragmentEntity> findLatestForLocale(String tenantId, String locale) {
        return findByTenantIdAndLocaleOrderByVersionDesc(tenantId, locale).stream().findFirst();
    }
}
