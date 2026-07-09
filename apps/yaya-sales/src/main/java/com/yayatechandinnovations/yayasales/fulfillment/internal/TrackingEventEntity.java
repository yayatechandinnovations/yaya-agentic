package com.yayatechandinnovations.yayasales.fulfillment.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Append-only (§8.7) — the table trigger rejects UPDATE/DELETE outright. */
@Entity
@Table(name = "tracking_events")
public class TrackingEventEntity {

    @Id
    private String id;

    @Column(name = "shipment_id", nullable = false)
    private String shipmentId;

    @Column(nullable = false)
    private String status;

    @Column
    private String description;

    @Column
    private String location;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    protected TrackingEventEntity() {}

    public TrackingEventEntity(String id, String shipmentId, String status, String description,
                               String location, Instant occurredAt) {
        this.id = id;
        this.shipmentId = shipmentId;
        this.status = status;
        this.description = description;
        this.location = location;
        this.occurredAt = occurredAt;
        this.recordedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getShipmentId() { return shipmentId; }
    public String getStatus() { return status; }
    public String getDescription() { return description; }
    public String getLocation() { return location; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getRecordedAt() { return recordedAt; }
}
