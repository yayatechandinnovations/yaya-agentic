package com.yayatechandinnovations.yayasales.stores.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LocationRepository extends JpaRepository<LocationEntity, String> {

    List<LocationEntity> findByStoreIdOrderBySortOrderAsc(String storeId);
}
