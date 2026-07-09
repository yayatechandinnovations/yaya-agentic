package com.yayatechandinnovations.yayasales.fulfillment.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ShippingRateRepository extends JpaRepository<ShippingRateEntity, String> {

    List<ShippingRateEntity> findByStoreId(String storeId);

    List<ShippingRateEntity> findByZoneIdIn(List<String> zoneIds);
}
