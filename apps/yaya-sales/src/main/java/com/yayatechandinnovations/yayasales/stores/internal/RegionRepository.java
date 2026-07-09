package com.yayatechandinnovations.yayasales.stores.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RegionRepository extends JpaRepository<RegionEntity, String> {

    List<RegionEntity> findByStoreIdOrderByNameAsc(String storeId);
}
