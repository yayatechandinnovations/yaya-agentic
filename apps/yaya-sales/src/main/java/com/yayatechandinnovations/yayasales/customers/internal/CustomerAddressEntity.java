package com.yayatechandinnovations.yayasales.customers.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "customer_addresses")
public class CustomerAddressEntity {

    @Id
    private String id;

    @Column(nullable = false)
    private String kind;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data_json", nullable = false)
    private String dataJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CustomerAddressEntity() {}

    public CustomerAddressEntity(String id, String kind, String dataJson, Instant createdAt) {
        this.id = id;
        this.kind = kind;
        this.dataJson = dataJson;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getKind() { return kind; }
    public String getDataJson() { return dataJson; }
    public Instant getCreatedAt() { return createdAt; }

    public void setKind(String kind) { this.kind = kind; }
    public void setDataJson(String dataJson) { this.dataJson = dataJson; }
}
