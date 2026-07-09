package com.yayatechandinnovations.yayasales.pricing.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PriceListRepository extends JpaRepository<PriceListEntity, String> {

    List<PriceListEntity> findByStoreIdAndActiveTrue(String storeId);

    List<PriceListEntity> findByStoreId(String storeId);
}
