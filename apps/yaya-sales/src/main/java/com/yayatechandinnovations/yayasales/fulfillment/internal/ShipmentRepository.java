package com.yayatechandinnovations.yayasales.fulfillment.internal;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ShipmentRepository extends JpaRepository<ShipmentEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ShipmentEntity s where s.id = :id")
    Optional<ShipmentEntity> lockById(@Param("id") String id);

    List<ShipmentEntity> findByOrderIdOrderByCreatedAtAsc(String orderId);

    Optional<ShipmentEntity> findFirstByCarrierIdAndTrackingNumber(String carrierId, String trackingNumber);
}
