package com.yayatechandinnovations.yayasales.fulfillment.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeliveryAssignmentRepository
        extends JpaRepository<DeliveryAssignmentEntity, String> {

    List<DeliveryAssignmentEntity> findByAgentIdOrderByAssignedAtDesc(String agentId);

    List<DeliveryAssignmentEntity> findByShipmentIdOrderByAssignedAtDesc(String shipmentId);
}
