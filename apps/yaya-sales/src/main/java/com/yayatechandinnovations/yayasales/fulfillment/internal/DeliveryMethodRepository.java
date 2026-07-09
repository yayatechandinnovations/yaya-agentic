package com.yayatechandinnovations.yayasales.fulfillment.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeliveryMethodRepository extends JpaRepository<DeliveryMethodEntity, String> {

    List<DeliveryMethodEntity> findByStoreId(String storeId);
}
