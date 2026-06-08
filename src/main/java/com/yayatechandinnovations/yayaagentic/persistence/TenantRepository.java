package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TenantRepository extends JpaRepository<TenantEntity, String> {

    List<TenantEntity> findAllByStatus(String status);

    Optional<TenantEntity> findByIdAndStatus(String id, String status);

    default Optional<TenantEntity> findActiveById(String id) {
        return findByIdAndStatus(id, TenantEntity.Status.ACTIVE.name());
    }

    default boolean isActive(String id) {
        return findActiveById(id).isPresent();
    }
}
