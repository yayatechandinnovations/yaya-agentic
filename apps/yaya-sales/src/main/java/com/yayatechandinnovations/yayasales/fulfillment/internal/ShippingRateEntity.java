package com.yayatechandinnovations.yayasales.fulfillment.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "shipping_rates")
public class ShippingRateEntity {

    @Id
    private String id;

    @Column(name = "store_id", nullable = false)
    private String storeId;

    @Column(name = "zone_id", nullable = false)
    private String zoneId;

    @Column(name = "method_id", nullable = false)
    private String methodId;

    @Column(name = "carrier_id")
    private String carrierId;

    @Column(name = "service_level")
    private String serviceLevel;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rule_json", nullable = false)
    private String ruleJson;

    @Column(name = "handling_fee_minor", nullable = false)
    private long handlingFeeMinor;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "transit_json")
    private String transitJson;

    protected ShippingRateEntity() {}

    public ShippingRateEntity(String id, String storeId, String zoneId, String methodId,
                              String carrierId, String serviceLevel, String ruleJson,
                              long handlingFeeMinor, String transitJson) {
        this.id = id;
        this.storeId = storeId;
        this.zoneId = zoneId;
        this.methodId = methodId;
        this.carrierId = carrierId;
        this.serviceLevel = serviceLevel;
        this.ruleJson = ruleJson;
        this.handlingFeeMinor = handlingFeeMinor;
        this.transitJson = transitJson;
    }

    public String getId() { return id; }
    public String getStoreId() { return storeId; }
    public String getZoneId() { return zoneId; }
    public String getMethodId() { return methodId; }
    public String getCarrierId() { return carrierId; }
    public String getServiceLevel() { return serviceLevel; }
    public String getRuleJson() { return ruleJson; }
    public long getHandlingFeeMinor() { return handlingFeeMinor; }
    public String getTransitJson() { return transitJson; }
}
