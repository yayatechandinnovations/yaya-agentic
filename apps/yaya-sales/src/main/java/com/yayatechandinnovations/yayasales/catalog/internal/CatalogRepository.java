package com.yayatechandinnovations.yayasales.catalog.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CatalogRepository extends JpaRepository<CatalogEntity, String> {

    List<CatalogEntity> findByStoreIdOrderByCreatedAtAsc(String storeId);
}
