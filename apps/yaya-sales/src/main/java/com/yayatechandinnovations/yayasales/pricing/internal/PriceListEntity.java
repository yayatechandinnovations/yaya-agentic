package com.yayatechandinnovations.yayasales.pricing.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "price_lists")
public class PriceListEntity {

    @Id
    private String id;

    @Column(name = "store_id", nullable = false)
    private String storeId;

    @Column(name = "region_id")
    private String regionId;

    @Column(name = "channel_id")
    private String channelId;

    @Column(name = "customer_group")
    private String customerGroup;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rules_json", nullable = false)
    private String rulesJson;

    @Column(nullable = false)
    private boolean active;

    protected PriceListEntity() {}

    public PriceListEntity(String id, String storeId, String regionId, String channelId,
                           String customerGroup, String rulesJson) {
        this.id = id;
        this.storeId = storeId;
        this.regionId = regionId;
        this.channelId = channelId;
        this.customerGroup = customerGroup;
        this.rulesJson = rulesJson;
        this.active = true;
    }

    public String getId() { return id; }
    public String getStoreId() { return storeId; }
    public String getRegionId() { return regionId; }
    public String getChannelId() { return channelId; }
    public String getCustomerGroup() { return customerGroup; }
    public String getRulesJson() { return rulesJson; }
    public boolean isActive() { return active; }

    public void setRulesJson(String rulesJson) { this.rulesJson = rulesJson; }
    public void setActive(boolean active) { this.active = active; }
}
