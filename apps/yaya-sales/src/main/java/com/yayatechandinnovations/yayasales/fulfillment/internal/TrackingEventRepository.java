package com.yayatechandinnovations.yayasales.fulfillment.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TrackingEventRepository extends JpaRepository<TrackingEventEntity, String> {

    List<TrackingEventEntity> findByShipmentIdOrderByOccurredAtAscRecordedAtAsc(String shipmentId);
}
