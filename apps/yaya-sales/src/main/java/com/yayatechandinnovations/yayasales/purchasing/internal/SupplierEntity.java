package com.yayatechandinnovations.yayasales.purchasing.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "suppliers")
public class SupplierEntity {

    @Id
    private String id;

    @Column(name = "store_id", nullable = false)
    private String storeId;

    @Column(nullable = false)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "contact_json")
    private String contactJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SupplierEntity() {}

    public SupplierEntity(String id, String storeId, String name, String contactJson) {
        this.id = id;
        this.storeId = storeId;
        this.name = name;
        this.contactJson = contactJson;
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public String getStoreId() { return storeId; }
    public String getName() { return name; }
    public String getContactJson() { return contactJson; }
    public Instant getCreatedAt() { return createdAt; }

    void setName(String name) { this.name = name; }
    void setContactJson(String contactJson) { this.contactJson = contactJson; }
}
