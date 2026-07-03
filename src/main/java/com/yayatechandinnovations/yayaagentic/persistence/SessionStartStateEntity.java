package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * JPA row for a paused Session Start Flow cursor (table
 * {@code session_start_state}). See {@code docs/design/session-start-flows.md} §7.3.
 */
@Entity
@Table(name = "session_start_state")
public class SessionStartStateEntity {

    @Id
    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "flow_id", length = 128, nullable = false)
    private String flowId;

    @Column(name = "flow_version", nullable = false)
    private Integer flowVersion;

    @Column(name = "current_step_key", length = 128, nullable = false)
    private String currentStepKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scratch_json", columnDefinition = "jsonb", nullable = false)
    private String scratchJson = "{}";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "step_outputs_json", columnDefinition = "jsonb", nullable = false)
    private String stepOutputsJson = "{}";

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected SessionStartStateEntity() {}

    public SessionStartStateEntity(String sessionId, String flowId, int flowVersion, String currentStepKey) {
        this.sessionId = sessionId;
        this.flowId = flowId;
        this.flowVersion = flowVersion;
        this.currentStepKey = currentStepKey;
    }

    public String getSessionId() { return sessionId; }
    public String getFlowId() { return flowId; }
    public Integer getFlowVersion() { return flowVersion; }
    public String getCurrentStepKey() { return currentStepKey; }
    public String getScratchJson() { return scratchJson; }
    public String getStepOutputsJson() { return stepOutputsJson; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setFlowId(String v) { this.flowId = v; }
    public void setFlowVersion(int v) { this.flowVersion = v; }
    public void setCurrentStepKey(String v) { this.currentStepKey = v; }
    public void setScratchJson(String v) { this.scratchJson = v; }
    public void setStepOutputsJson(String v) { this.stepOutputsJson = v; }
    public void setUpdatedAt(OffsetDateTime v) { this.updatedAt = v; }
}
