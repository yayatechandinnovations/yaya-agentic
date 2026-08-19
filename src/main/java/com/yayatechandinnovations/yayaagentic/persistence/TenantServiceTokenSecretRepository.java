package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantServiceTokenSecretRepository
        extends JpaRepository<TenantServiceTokenSecretEntity, String> {
}
