package com.yayatechandinnovations.yayasales.fulfillment.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "shipping_zones")
public class ShippingZoneEntity {

    @Id
    private String id;

    @Column(name = "store_id", nullable = false)
    private String storeId;

    @Column(nullable = false)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "regions_json", nullable = false)
    private String regionsJson;

    protected ShippingZoneEntity() {}

    public ShippingZoneEntity(String id, String storeId, String name, String regionsJson) {
        this.id = id;
        this.storeId = storeId;
        this.name = name;
        this.regionsJson = regionsJson;
    }

    public String getId() { return id; }
    public String getStoreId() { return storeId; }
    public String getName() { return name; }
    public String getRegionsJson() { return regionsJson; }
}
