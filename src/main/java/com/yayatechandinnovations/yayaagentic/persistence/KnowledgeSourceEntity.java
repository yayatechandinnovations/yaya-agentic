package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Persistence of a {@code KnowledgeSource}. The record-level
 * {@code SourceLocation}, {@code IngestionPolicy}, {@code RetrievalPolicy}
 * and {@code PermissionRequirement} are JSON-encoded; rehydration happens
 * at runtime-catalog load time.
 */
@Entity
@Table(name = "knowledge_sources")
@IdClass(KnowledgeSourceEntity.PK.class)
public class KnowledgeSourceEntity {

    @Id @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Id @Column(length = 128)
    private String id;

    @Id
    private Integer version;

    @Column(nullable = false)
    private String name;

    @Column(name = "location_kind", nullable = false, length = 32)
    private String locationKind;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "location_json", columnDefinition = "jsonb", nullable = false)
    private String locationJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ingestion_policy_json", columnDefinition = "jsonb", nullable = false)
    private String ingestionPolicyJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "retrieval_policy_json", columnDefinition = "jsonb", nullable = false)
    private String retrievalPolicyJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "access_requirement_json", columnDefinition = "jsonb", nullable = false)
    private String accessRequirementJson = "{}";

    @Column(nullable = false, length = 16)
    private String status = "UNINDEXED";

    @Column(name = "last_indexed_at")
    private OffsetDateTime lastIndexedAt;

    @Column(name = "doc_count", nullable = false)
    private Integer docCount = 0;

    @Column(name = "chunk_count", nullable = false)
    private Integer chunkCount = 0;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected KnowledgeSourceEntity() {}

    public KnowledgeSourceEntity(String tenantId, String id, int version,
                                 String name, String locationKind,
                                 String locationJson, String ingestionPolicyJson,
                                 String retrievalPolicyJson) {
        this.tenantId = tenantId;
        this.id = id;
        this.version = version;
        this.name = name;
        this.locationKind = locationKind;
        this.locationJson = locationJson;
        this.ingestionPolicyJson = ingestionPolicyJson;
        this.retrievalPolicyJson = retrievalPolicyJson;
    }

    public String getTenantId() { return tenantId; }
    public String getId() { return id; }
    public Integer getVersion() { return version; }
    public String getName() { return name; }
    public String getLocationKind() { return locationKind; }
    public String getLocationJson() { return locationJson; }
    public String getIngestionPolicyJson() { return ingestionPolicyJson; }
    public String getRetrievalPolicyJson() { return retrievalPolicyJson; }
    public String getAccessRequirementJson() { return accessRequirementJson; }
    public String getStatus() { return status; }
    public OffsetDateTime getLastIndexedAt() { return lastIndexedAt; }
    public Integer getDocCount() { return docCount; }
    public Integer getChunkCount() { return chunkCount; }
    public String getLastError() { return lastError; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public void setName(String v) { this.name = v; }
    public void setLocationKind(String v) { this.locationKind = v; }
    public void setLocationJson(String v) { this.locationJson = v; }
    public void setIngestionPolicyJson(String v) { this.ingestionPolicyJson = v; }
    public void setRetrievalPolicyJson(String v) { this.retrievalPolicyJson = v; }
    public void setAccessRequirementJson(String v) { this.accessRequirementJson = v; }
    public void setStatus(String v) { this.status = v; }
    public void setLastIndexedAt(OffsetDateTime v) { this.lastIndexedAt = v; }
    public void setDocCount(int v) { this.docCount = v; }
    public void setChunkCount(int v) { this.chunkCount = v; }
    public void setLastError(String v) { this.lastError = v; }

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
