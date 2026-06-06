package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "turns")
public class TurnEntity {

    @Id
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(nullable = false)
    private Integer idx;

    @Column(nullable = false, length = 16)
    private String role;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content_json", columnDefinition = "jsonb", nullable = false)
    private String contentJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_calls_json", columnDefinition = "jsonb", nullable = false)
    private String toolCallsJson = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_results_json", columnDefinition = "jsonb", nullable = false)
    private String toolResultsJson = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "retrieved_ids_json", columnDefinition = "jsonb", nullable = false)
    private String retrievedIdsJson = "[]";

    @Column(length = 128)
    private String model;

    @Column(name = "tokens_in")
    private Integer tokensIn;

    @Column(name = "tokens_out")
    private Integer tokensOut;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected TurnEntity() {}

    public TurnEntity(UUID id, UUID sessionId, int idx, String role,
                      String contentJson, OffsetDateTime createdAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.idx = idx;
        this.role = role;
        this.contentJson = contentJson;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getSessionId() { return sessionId; }
    public Integer getIdx() { return idx; }
    public String getRole() { return role; }
    public String getContentJson() { return contentJson; }
    public String getToolCallsJson() { return toolCallsJson; }
    public String getToolResultsJson() { return toolResultsJson; }
    public String getRetrievedIdsJson() { return retrievedIdsJson; }
    public String getModel() { return model; }
    public Integer getTokensIn() { return tokensIn; }
    public Integer getTokensOut() { return tokensOut; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public void setContentJson(String v) { this.contentJson = v; }
    public void setToolCallsJson(String v) { this.toolCallsJson = v; }
    public void setToolResultsJson(String v) { this.toolResultsJson = v; }
    public void setRetrievedIdsJson(String v) { this.retrievedIdsJson = v; }
    public void setModel(String v) { this.model = v; }
    public void setTokensIn(Integer v) { this.tokensIn = v; }
    public void setTokensOut(Integer v) { this.tokensOut = v; }
}
