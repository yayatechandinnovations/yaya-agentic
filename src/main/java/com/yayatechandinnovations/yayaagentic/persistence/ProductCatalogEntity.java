package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Persistence for a {@code ProductCatalog}. Mirrors {@code ProfileEntity}
 * exactly (composite {@code (tenant_id, id, version)} key, JSONB columns) so
 * operators and storage feel familiar. See {@code ecommerce-chat-design.md} §14.
 */
@Entity
@Table(name = "product_catalogs")
@IdClass(ProductCatalogEntity.PK.class)
public class ProductCatalogEntity {

    @Id @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Id @Column(length = 128)
    private String id;

    @Id
    private Integer version;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "location_json", columnDefinition = "jsonb", nullable = false)
    private String locationJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sync_json", columnDefinition = "jsonb")
    private String syncJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "search_json", columnDefinition = "jsonb")
    private String searchJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "access_json", columnDefinition = "jsonb")
    private String accessJson;

    @Column(nullable = false, length = 8)
    private String currency;

    @Column(name = "image_url", length = 1024)
    private String imageUrl;

    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "deprecated_at")
    private OffsetDateTime deprecatedAt;

    protected ProductCatalogEntity() {}

    public ProductCatalogEntity(String tenantId, String id, int version,
                                String displayName, String currency) {
        this.tenantId = tenantId;
        this.id = id;
        this.version = version;
        this.displayName = displayName;
        this.currency = currency;
    }

    public String getTenantId() { return tenantId; }
    public String getId() { return id; }
    public Integer getVersion() { return version; }
    public String getDisplayName() { return displayName; }
    public String getLocationJson() { return locationJson; }
    public String getSyncJson() { return syncJson; }
    public String getSearchJson() { return searchJson; }
    public String getAccessJson() { return accessJson; }
    public String getCurrency() { return currency; }
    public String getImageUrl() { return imageUrl; }
    public String getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getDeprecatedAt() { return deprecatedAt; }

    public void setDisplayName(String v) { this.displayName = v; }
    public void setLocationJson(String v) { this.locationJson = v; }
    public void setSyncJson(String v) { this.syncJson = v; }
    public void setSearchJson(String v) { this.searchJson = v; }
    public void setAccessJson(String v) { this.accessJson = v; }
    public void setCurrency(String v) { this.currency = v; }
    public void setImageUrl(String v) { this.imageUrl = v; }
    public void setStatus(String v) { this.status = v; }

    public static class PK implements Serializable {
        private String tenantId;
        private String id;
        private Integer version;

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
