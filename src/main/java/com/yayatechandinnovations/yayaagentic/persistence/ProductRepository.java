package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

/**
 * Spring Data access to {@link ProductEntity}. {@link JpaSpecificationExecutor}
 * powers the admin's paged/filtered/sorted product table; the derived finders
 * feed {@code DatabaseCatalogLoader} (enumerate a catalog's active products) and
 * catalog product counts.
 */
public interface ProductRepository
        extends JpaRepository<ProductEntity, ProductEntity.PK>,
                JpaSpecificationExecutor<ProductEntity> {

    List<ProductEntity> findByTenantIdAndCatalogIdAndStatus(
            String tenantId, String catalogId, String status, Sort sort);

    long countByTenantIdAndCatalogIdAndStatus(String tenantId, String catalogId, String status);
}
