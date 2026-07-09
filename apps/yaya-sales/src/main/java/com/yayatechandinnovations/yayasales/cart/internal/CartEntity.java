package com.yayatechandinnovations.yayasales.cart.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "carts")
public class CartEntity {

    @Id
    private String id;

    @Column(name = "store_id", nullable = false)
    private String storeId;

    @Column(name = "channel_id", nullable = false)
    private String channelId;

    @Column(name = "region_id", nullable = false)
    private String regionId;

    @Column(name = "customer_ref")
    private String customerRef;

    @Column(nullable = false)
    private String currency;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "lines_json", nullable = false)
    private String linesJson;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CartEntity() {}

    public CartEntity(String id, String storeId, String channelId, String regionId,
                      String customerRef, String currency, String linesJson) {
        this.id = id;
        this.storeId = storeId;
        this.channelId = channelId;
        this.regionId = regionId;
        this.customerRef = customerRef;
        this.currency = currency;
        this.linesJson = linesJson;
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getStoreId() { return storeId; }
    public String getChannelId() { return channelId; }
    public String getRegionId() { return regionId; }
    public String getCustomerRef() { return customerRef; }
    public String getCurrency() { return currency; }
    public String getLinesJson() { return linesJson; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setLinesJson(String linesJson) {
        this.linesJson = linesJson;
        this.updatedAt = Instant.now();
    }

    public void setCustomerRef(String customerRef) {
        this.customerRef = customerRef;
        this.updatedAt = Instant.now();
    }
}
