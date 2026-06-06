package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "recorder_outbox")
public class RecorderOutboxEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "turn_id")
    private UUID turnId;

    @Column(name = "sink_id", nullable = false, length = 128)
    private String sinkId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", columnDefinition = "jsonb", nullable = false)
    private String payloadJson;

    @Column(nullable = false)
    private Integer attempts = 0;

    @Column(name = "next_attempt_at", nullable = false)
    private OffsetDateTime nextAttemptAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "dispatched_at")
    private OffsetDateTime dispatchedAt;

    @Column(nullable = false, length = 16)
    private String status = "PENDING";

    protected RecorderOutboxEntity() {}

    public RecorderOutboxEntity(String tenantId, UUID sessionId, UUID turnId,
                                String sinkId, String payloadJson,
                                OffsetDateTime nextAttemptAt) {
        this.tenantId = tenantId;
        this.sessionId = sessionId;
        this.turnId = turnId;
        this.sinkId = sinkId;
        this.payloadJson = payloadJson;
        this.nextAttemptAt = nextAttemptAt;
    }

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public UUID getSessionId() { return sessionId; }
    public UUID getTurnId() { return turnId; }
    public String getSinkId() { return sinkId; }
    public String getPayloadJson() { return payloadJson; }
    public Integer getAttempts() { return attempts; }
    public OffsetDateTime getNextAttemptAt() { return nextAttemptAt; }
    public OffsetDateTime getDispatchedAt() { return dispatchedAt; }
    public String getStatus() { return status; }

    public void markSent(OffsetDateTime when) {
        this.status = "SENT";
        this.dispatchedAt = when;
    }

    public void scheduleRetry(OffsetDateTime nextAttempt) {
        this.attempts = (attempts == null ? 0 : attempts) + 1;
        this.nextAttemptAt = nextAttempt;
    }

    public void markFailed() {
        this.status = "FAILED";
    }
}
