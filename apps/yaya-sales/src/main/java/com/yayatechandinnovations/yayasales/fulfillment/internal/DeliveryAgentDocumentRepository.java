package com.yayatechandinnovations.yayasales.fulfillment.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeliveryAgentDocumentRepository
        extends JpaRepository<DeliveryAgentDocumentEntity, String> {

    List<DeliveryAgentDocumentEntity> findByAgentIdOrderByUploadedAtAsc(String agentId);
}
