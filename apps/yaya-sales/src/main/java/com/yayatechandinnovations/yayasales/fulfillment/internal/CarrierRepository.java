package com.yayatechandinnovations.yayasales.fulfillment.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CarrierRepository extends JpaRepository<CarrierEntity, String> {

    List<CarrierEntity> findByStoreId(String storeId);
}
