package com.yayatechandinnovations.yayasales.catalog.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "catalogs")
public class CatalogEntity {

    @Id
    private String id;

    @Column(name = "store_id", nullable = false)
    private String storeId;

    @Column(nullable = false)
    private int version;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "deprecated_at")
    private Instant deprecatedAt;

    protected CatalogEntity() {}

    public CatalogEntity(String id, String storeId, String displayName, Instant createdAt) {
        this.id = id;
        this.storeId = storeId;
        this.version = 1;
        this.displayName = displayName;
        this.status = "DRAFT";
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getStoreId() { return storeId; }
    public int getVersion() { return version; }
    public String getDisplayName() { return displayName; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }

    public void publish() {
        if (!"DRAFT".equals(status)) {
            this.version++;
        }
        this.status = "PUBLISHED";
    }
}
