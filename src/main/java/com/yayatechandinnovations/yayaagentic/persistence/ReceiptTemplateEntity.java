package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * One tenant-authored receipt template ({@code chat-experience-design.md} §7.4):
 * an ordered array of generic layout elements with {@code {{path}}} data
 * binding. The vocabulary is domain-free by contract — the service validates
 * element types; the meaning lives entirely in tenant data.
 */
@Entity
@Table(name = "receipt_templates")
public class ReceiptTemplateEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "kind_label", length = 60)
    private String kindLabel;

    @Column(name = "paper_cols", nullable = false)
    private int paperCols = 32;

    @Column(name = "paper_profile", nullable = false, length = 8)
    private String paperProfile = "58mm";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "elements", columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> elements;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "brand", columnDefinition = "jsonb")
    private Map<String, Object> brand;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false)
    private OffsetDateTime updatedAt;

    protected ReceiptTemplateEntity() {}

    public ReceiptTemplateEntity(String id, String tenantId, String name, String kindLabel,
                                 int paperCols, String paperProfile,
                                 List<Map<String, Object>> elements, Map<String, Object> brand) {
        this.id = id;
        this.tenantId = tenantId;
        this.name = name;
        this.kindLabel = kindLabel;
        this.paperCols = paperCols;
        this.paperProfile = paperProfile;
        this.elements = elements;
        this.brand = brand;
    }

    @PreUpdate
    void touch() { this.updatedAt = OffsetDateTime.now(); }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getName() { return name; }
    public String getKindLabel() { return kindLabel; }
    public int getPaperCols() { return paperCols; }
    public String getPaperProfile() { return paperProfile; }
    public List<Map<String, Object>> getElements() { return elements; }
    public Map<String, Object> getBrand() { return brand; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setName(String name) { this.name = name; }
    public void setKindLabel(String kindLabel) { this.kindLabel = kindLabel; }
    public void setPaperCols(int paperCols) { this.paperCols = paperCols; }
    public void setPaperProfile(String paperProfile) { this.paperProfile = paperProfile; }
    public void setElements(List<Map<String, Object>> elements) { this.elements = elements; }
    public void setBrand(Map<String, Object> brand) { this.brand = brand; }
}
