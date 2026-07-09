package com.yayatechandinnovations.yayaidentity.organizations;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrganizationRepository extends JpaRepository<OrganizationEntity, String> {

    boolean existsBySlug(String slug);

    Optional<OrganizationEntity> findBySlug(String slug);
}
