package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * A single managed product row — the mutable, per-row unit that replaced the
 * inline product array in {@code product_catalogs.location_json}. Keyed by
 * {@code (tenant_id, catalog_id, product_id)}; {@code product_id} is the merchant
 * SKU. Editing a field is a single-row update, never a catalog version bump. See
 * {@code commerce-redesign-authoring-rendering-paypal.md} Pillar A.
 */
@Entity
@Table(name = "products")
@IdClass(ProductEntity.PK.class)
public class ProductEntity {

    @Id @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Id @Column(name = "catalog_id", length = 128)
    private String catalogId;

    @Id @Column(name = "product_id", length = 128)
    private String productId;

    @Column(nullable = false, length = 512)
    private String title;

    @Column(name = "short_description")
    private String shortDescription;

    @Column(name = "long_description")
    private String longDescription;

    @Column(name = "price_minor", nullable = false)
    private long priceMinor;

    @Column(nullable = false, length = 8)
    private String currency;

    @Column(length = 255)
    private String category;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attributes_json", columnDefinition = "jsonb", nullable = false)
    private String attributesJson = "{}";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "variants_json", columnDefinition = "jsonb", nullable = false)
    private String variantsJson = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "image_urls_json", columnDefinition = "jsonb", nullable = false)
    private String imageUrlsJson = "[]";

    @Column(nullable = false, length = 16)
    private String availability = "IN_STOCK";

    private Integer inventory;

    @Column(name = "detail_url", length = 1024)
    private String detailUrl;

    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected ProductEntity() {}

    public ProductEntity(String tenantId, String catalogId, String productId) {
        this.tenantId = tenantId;
        this.catalogId = catalogId;
        this.productId = productId;
    }

    public String getTenantId() { return tenantId; }
    public String getCatalogId() { return catalogId; }
    public String getProductId() { return productId; }
    public String getTitle() { return title; }
    public String getShortDescription() { return shortDescription; }
    public String getLongDescription() { return longDescription; }
    public long getPriceMinor() { return priceMinor; }
    public String getCurrency() { return currency; }
    public String getCategory() { return category; }
    public String getAttributesJson() { return attributesJson; }
    public String getVariantsJson() { return variantsJson; }
    public String getImageUrlsJson() { return imageUrlsJson; }
    public String getAvailability() { return availability; }
    public Integer getInventory() { return inventory; }
    public String getDetailUrl() { return detailUrl; }
    public String getStatus() { return status; }
    public int getSortOrder() { return sortOrder; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setTitle(String v) { this.title = v; }
    public void setShortDescription(String v) { this.shortDescription = v; }
    public void setLongDescription(String v) { this.longDescription = v; }
    public void setPriceMinor(long v) { this.priceMinor = v; }
    public void setCurrency(String v) { this.currency = v; }
    public void setCategory(String v) { this.category = v; }
    public void setAttributesJson(String v) { this.attributesJson = v; }
    public void setVariantsJson(String v) { this.variantsJson = v; }
    public void setImageUrlsJson(String v) { this.imageUrlsJson = v; }
    public void setAvailability(String v) { this.availability = v; }
    public void setInventory(Integer v) { this.inventory = v; }
    public void setDetailUrl(String v) { this.detailUrl = v; }
    public void setStatus(String v) { this.status = v; }
    public void setSortOrder(int v) { this.sortOrder = v; }
    public void setUpdatedAt(OffsetDateTime v) { this.updatedAt = v; }

    public static class PK implements Serializable {
        private String tenantId;
        private String catalogId;
        private String productId;

        public PK() {}
        public PK(String tenantId, String catalogId, String productId) {
            this.tenantId = tenantId; this.catalogId = catalogId; this.productId = productId;
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(tenantId, pk.tenantId)
                    && Objects.equals(catalogId, pk.catalogId)
                    && Objects.equals(productId, pk.productId);
        }
        @Override public int hashCode() { return Objects.hash(tenantId, catalogId, productId); }
    }
}
