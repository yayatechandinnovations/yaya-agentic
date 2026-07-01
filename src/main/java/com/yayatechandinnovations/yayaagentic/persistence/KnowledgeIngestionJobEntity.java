package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of {@code knowledge_ingestion_jobs} (V52) — a queued unit of ingestion
 * work for a specific {@code (tenant, source, version)}. Lifecycle:
 * QUEUED → RUNNING → DONE, or → QUEUED (retry with backoff) → FAILED (terminal
 * once attempts hit {@code maxAttempts}). See {@code IngestionJobService}.
 */
@Entity
@Table(name = "knowledge_ingestion_jobs")
public class KnowledgeIngestionJobEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "source_id", nullable = false, length = 128)
    private String sourceId;

    @Column(name = "source_version", nullable = false)
    private int sourceVersion;

    @Column(nullable = false, length = 16)
    private String state = "QUEUED";

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 5;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "run_after", nullable = false)
    private OffsetDateTime runAfter;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected KnowledgeIngestionJobEntity() {}

    public KnowledgeIngestionJobEntity(UUID id, String tenantId, String sourceId,
                                       int sourceVersion, int maxAttempts,
                                       OffsetDateTime now) {
        this.id = id;
        this.tenantId = tenantId;
        this.sourceId = sourceId;
        this.sourceVersion = sourceVersion;
        this.maxAttempts = maxAttempts;
        this.runAfter = now;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getSourceId() { return sourceId; }
    public int getSourceVersion() { return sourceVersion; }
    public String getState() { return state; }
    public int getAttempts() { return attempts; }
    public int getMaxAttempts() { return maxAttempts; }
    public String getLastError() { return lastError; }
    public OffsetDateTime getRunAfter() { return runAfter; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setState(String v) { this.state = v; }
    public void setAttempts(int v) { this.attempts = v; }
    public void setLastError(String v) { this.lastError = v; }
    public void setRunAfter(OffsetDateTime v) { this.runAfter = v; }
    public void setUpdatedAt(OffsetDateTime v) { this.updatedAt = v; }
}
