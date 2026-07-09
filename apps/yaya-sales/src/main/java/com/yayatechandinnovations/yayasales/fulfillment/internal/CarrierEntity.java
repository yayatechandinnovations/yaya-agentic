package com.yayatechandinnovations.yayasales.fulfillment.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "carriers")
public class CarrierEntity {

    @Id
    private String id;

    @Column(name = "store_id", nullable = false)
    private String storeId;

    @Column(nullable = false)
    private String kind;

    @Column(nullable = false)
    private String name;

    @Column(name = "credentials_ref")
    private String credentialsRef;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_json")
    private String configJson;

    protected CarrierEntity() {}

    public CarrierEntity(String id, String storeId, String kind, String name, String configJson) {
        this.id = id;
        this.storeId = storeId;
        this.kind = kind;
        this.name = name;
        this.configJson = configJson;
    }

    public String getId() { return id; }
    public String getStoreId() { return storeId; }
    public String getKind() { return kind; }
    public String getName() { return name; }
    public String getCredentialsRef() { return credentialsRef; }
    public String getConfigJson() { return configJson; }
}
