package com.yayatechandinnovations.yayasales.platform.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "outbox")
public class OutboxEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_id")
    private String storeId;

    @Column(nullable = false)
    private String aggregate;

    @Column(name = "aggregate_id")
    private String aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", nullable = false)
    private String payloadJson;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEntity() {}

    public OutboxEntity(String storeId, String aggregate, String aggregateId,
                        String eventType, String payloadJson, Instant createdAt) {
        this.storeId = storeId;
        this.aggregate = aggregate;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payloadJson = payloadJson;
        this.status = "PENDING";
        this.attempts = 0;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getStoreId() { return storeId; }
    public String getAggregate() { return aggregate; }
    public String getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public String getPayloadJson() { return payloadJson; }
    public String getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public Instant getCreatedAt() { return createdAt; }

    public void markPublished(Instant at) {
        this.status = "PUBLISHED";
        this.publishedAt = at;
    }

    public void markAttemptFailed() {
        this.attempts++;
    }
}
