package com.yayatechandinnovations.yayasales.purchasing.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupplierRepository extends JpaRepository<SupplierEntity, String> {

    List<SupplierEntity> findByStoreIdOrderByNameAsc(String storeId);
}
