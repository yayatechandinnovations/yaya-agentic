package com.yayatechandinnovations.yayasales.fulfillment.internal;

import com.yayatechandinnovations.yayasales.fulfillment.DeliveryFleet.AssignmentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "delivery_assignments")
public class DeliveryAssignmentEntity {

    @Id
    private String id;

    @Column(name = "shipment_id", nullable = false)
    private String shipmentId;

    @Column(name = "agent_id", nullable = false)
    private String agentId;

    @Column(name = "store_id", nullable = false)
    private String storeId;

    @Column(nullable = false)
    private String status;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    protected DeliveryAssignmentEntity() {}

    public DeliveryAssignmentEntity(String id, String shipmentId, String agentId, String storeId) {
        this.id = id;
        this.shipmentId = shipmentId;
        this.agentId = agentId;
        this.storeId = storeId;
        this.status = AssignmentStatus.ASSIGNED.name();
        this.assignedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getShipmentId() { return shipmentId; }
    public String getAgentId() { return agentId; }
    public String getStoreId() { return storeId; }
    public String getStatus() { return status; }
    public Instant getAssignedAt() { return assignedAt; }

    public AssignmentStatus status() {
        return AssignmentStatus.valueOf(status);
    }

    public boolean active() {
        return status() == AssignmentStatus.ASSIGNED || status() == AssignmentStatus.ACCEPTED;
    }

    public void setStatus(AssignmentStatus status) { this.status = status.name(); }
}
