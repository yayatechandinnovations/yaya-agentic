package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductCatalogRepository
        extends JpaRepository<ProductCatalogEntity, ProductCatalogEntity.PK> {
    List<ProductCatalogEntity> findByTenantId(String tenantId);
}
