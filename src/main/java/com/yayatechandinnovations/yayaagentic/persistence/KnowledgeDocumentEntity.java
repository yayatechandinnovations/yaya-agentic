package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One ingested document (file, page, inline blob). content_hash is the
 * idempotency key — re-ingesting the same content updates indexed_at and
 * doesn't reshape the chunk tree.
 */
@Entity
@Table(name = "knowledge_documents")
public class KnowledgeDocumentEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "source_id", nullable = false, length = 128)
    private String sourceId;

    @Column(name = "source_version", nullable = false)
    private Integer sourceVersion;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(columnDefinition = "TEXT")
    private String uri;

    @Column(columnDefinition = "TEXT")
    private String title;

    @Column(name = "content_hash", length = 128)
    private String contentHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json", columnDefinition = "jsonb", nullable = false)
    private String metadataJson = "{}";

    @Column(name = "last_modified")
    private OffsetDateTime lastModified;

    @Column(name = "indexed_at")
    private OffsetDateTime indexedAt;

    protected KnowledgeDocumentEntity() {}

    public KnowledgeDocumentEntity(String tenantId, String sourceId, int sourceVersion,
                                   String uri, String title, String contentHash) {
        this.tenantId = tenantId;
        this.sourceId = sourceId;
        this.sourceVersion = sourceVersion;
        this.uri = uri;
        this.title = title;
        this.contentHash = contentHash;
    }

    public UUID getId() { return id; }
    public String getSourceId() { return sourceId; }
    public Integer getSourceVersion() { return sourceVersion; }
    public String getTenantId() { return tenantId; }
    public String getUri() { return uri; }
    public String getTitle() { return title; }
    public String getContentHash() { return contentHash; }
    public String getMetadataJson() { return metadataJson; }
    public OffsetDateTime getLastModified() { return lastModified; }
    public OffsetDateTime getIndexedAt() { return indexedAt; }

    public void setUri(String v) { this.uri = v; }
    public void setTitle(String v) { this.title = v; }
    public void setContentHash(String v) { this.contentHash = v; }
    public void setMetadataJson(String v) { this.metadataJson = v; }
    public void setLastModified(OffsetDateTime v) { this.lastModified = v; }
    public void setIndexedAt(OffsetDateTime v) { this.indexedAt = v; }
}
