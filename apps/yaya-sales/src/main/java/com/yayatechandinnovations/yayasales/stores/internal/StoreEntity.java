package com.yayatechandinnovations.yayasales.stores.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "stores")
public class StoreEntity {

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "default_currency", nullable = false)
    private String defaultCurrency;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "policies_json", nullable = false)
    private String policiesJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "branding_json")
    private String brandingJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected StoreEntity() {}

    public StoreEntity(String id, String tenantId, String displayName,
                       String defaultCurrency, String policiesJson, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.displayName = displayName;
        this.defaultCurrency = defaultCurrency;
        this.policiesJson = policiesJson;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getDisplayName() { return displayName; }
    public String getDefaultCurrency() { return defaultCurrency; }
    public String getPoliciesJson() { return policiesJson; }
    public Instant getCreatedAt() { return createdAt; }

    public void setPoliciesJson(String policiesJson) { this.policiesJson = policiesJson; }
}
