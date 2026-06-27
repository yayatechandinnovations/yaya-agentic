package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthResolverCallRepository
        extends JpaRepository<AuthResolverCallEntity, Long> {

    Page<AuthResolverCallEntity> findByTenantIdOrderByStartedAtDesc(
            String tenantId, Pageable pageable);

    Page<AuthResolverCallEntity> findByTenantIdAndCallerSha256OrderByStartedAtDesc(
            String tenantId, String callerSha256, Pageable pageable);
}
