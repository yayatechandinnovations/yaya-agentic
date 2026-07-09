package com.yayatechandinnovations.yayasales.fulfillment.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ShippingZoneRepository extends JpaRepository<ShippingZoneEntity, String> {

    List<ShippingZoneEntity> findByStoreId(String storeId);
}
