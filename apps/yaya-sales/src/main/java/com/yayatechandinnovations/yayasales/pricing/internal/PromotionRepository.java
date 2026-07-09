package com.yayatechandinnovations.yayasales.pricing.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PromotionRepository extends JpaRepository<PromotionEntity, String> {

    List<PromotionEntity> findByStoreIdAndActiveTrue(String storeId);

    List<PromotionEntity> findByStoreId(String storeId);
}
