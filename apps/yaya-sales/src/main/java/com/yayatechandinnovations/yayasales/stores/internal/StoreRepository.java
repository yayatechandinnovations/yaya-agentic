package com.yayatechandinnovations.yayasales.stores.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StoreRepository extends JpaRepository<StoreEntity, String> {

    List<StoreEntity> findByTenantIdOrderByCreatedAtAsc(String tenantId);
}
