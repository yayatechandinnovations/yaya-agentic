package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

@Entity
@Table(name = "capabilities")
@IdClass(CapabilityEntity.PK.class)
public class CapabilityEntity {

    @Id @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Id @Column(length = 128)
    private String id;

    @Id
    private Integer version;

    @Column(nullable = false)
    private String label;

    @Column(length = 4096)
    private String description;

    @Column(name = "llm_guidance", length = 4096)
    private String llmGuidance;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_ids_json", columnDefinition = "jsonb", nullable = false)
    private String toolIdsJson = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "requires_json", columnDefinition = "jsonb", nullable = false)
    private String requiresJson = "{}";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "follow_up_hints_json", columnDefinition = "jsonb", nullable = false)
    private String followUpHintsJson = "[]";

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected CapabilityEntity() {}

    public CapabilityEntity(String tenantId, String id, int version, String label) {
        this.tenantId = tenantId;
        this.id = id;
        this.version = version;
        this.label = label;
    }

    public String getTenantId() { return tenantId; }
    public String getId() { return id; }
    public Integer getVersion() { return version; }
    public String getLabel() { return label; }
    public String getDescription() { return description; }
    public String getLlmGuidance() { return llmGuidance; }
    public String getToolIdsJson() { return toolIdsJson; }
    public String getRequiresJson() { return requiresJson; }
    public String getFollowUpHintsJson() { return followUpHintsJson; }

    public void setLabel(String v) { this.label = v; }
    public void setDescription(String v) { this.description = v; }
    public void setLlmGuidance(String v) { this.llmGuidance = v; }
    public void setToolIdsJson(String v) { this.toolIdsJson = v; }
    public void setRequiresJson(String v) { this.requiresJson = v; }
    public void setFollowUpHintsJson(String v) { this.followUpHintsJson = v; }

    public static class PK implements Serializable {
        private String tenantId; private String id; private Integer version;
        public PK() {}
        public PK(String tenantId, String id, Integer version) {
            this.tenantId = tenantId; this.id = id; this.version = version;
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(tenantId, pk.tenantId)
                    && Objects.equals(id, pk.id)
                    && Objects.equals(version, pk.version);
        }
        @Override public int hashCode() { return Objects.hash(tenantId, id, version); }
    }
}
