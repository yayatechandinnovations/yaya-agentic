package com.yayatechandinnovations.yayasales.catalog.internal;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "products")
public class ProductEntity {

    @Id
    private String id;

    @Column(name = "store_id", nullable = false)
    private String storeId;

    @Column(name = "catalog_id", nullable = false)
    private String catalogId;

    @Column(nullable = false)
    private String title;

    private String description;

    private String category;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attributes_json")
    private String attributesJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "media_json")
    private String mediaJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json")
    private String metadataJson;

    @Column(nullable = false)
    private String status;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    @OrderBy("sku asc")
    private List<VariantEntity> variants = new ArrayList<>();

    protected ProductEntity() {}

    public ProductEntity(String id, String storeId, String catalogId, String title, Instant createdAt) {
        this.id = id;
        this.storeId = storeId;
        this.catalogId = catalogId;
        this.title = title;
        this.status = "DRAFT";
        this.sortOrder = 0;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public String getId() { return id; }
    public String getStoreId() { return storeId; }
    public String getCatalogId() { return catalogId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getCategory() { return category; }
    public String getAttributesJson() { return attributesJson; }
    public String getMediaJson() { return mediaJson; }
    public String getMetadataJson() { return metadataJson; }
    public String getStatus() { return status; }
    public int getSortOrder() { return sortOrder; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<VariantEntity> getVariants() { return variants; }

    public void setTitle(String title) { this.title = title; touch(); }
    public void setDescription(String description) { this.description = description; touch(); }
    public void setCategory(String category) { this.category = category; touch(); }
    public void setAttributesJson(String json) { this.attributesJson = json; touch(); }
    public void setMediaJson(String json) { this.mediaJson = json; touch(); }
    public void setMetadataJson(String json) { this.metadataJson = json; touch(); }
    public void setStatus(String status) { this.status = status; touch(); }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; touch(); }

    private void touch() { this.updatedAt = Instant.now(); }
}
