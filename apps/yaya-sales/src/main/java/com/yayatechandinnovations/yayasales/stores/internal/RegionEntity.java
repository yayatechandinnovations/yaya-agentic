package com.yayatechandinnovations.yayasales.stores.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "regions")
public class RegionEntity {

    @Id
    private String id;

    @Column(name = "store_id", nullable = false)
    private String storeId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String currency;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tax_json", nullable = false)
    private String taxJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tenders_json", nullable = false)
    private String tendersJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "zone_ids_json")
    private String zoneIdsJson;

    @Column(name = "price_list_id")
    private String priceListId;

    protected RegionEntity() {}

    public RegionEntity(String id, String storeId, String name, String currency,
                        String taxJson, String tendersJson) {
        this.id = id;
        this.storeId = storeId;
        this.name = name;
        this.currency = currency;
        this.taxJson = taxJson;
        this.tendersJson = tendersJson;
    }

    public String getId() { return id; }
    public String getStoreId() { return storeId; }
    public String getName() { return name; }
    public String getCurrency() { return currency; }
    public String getTaxJson() { return taxJson; }
    public String getTendersJson() { return tendersJson; }
    public String getZoneIdsJson() { return zoneIdsJson; }
    public String getPriceListId() { return priceListId; }

    public void setName(String name) { this.name = name; }
    public void setTaxJson(String taxJson) { this.taxJson = taxJson; }
    public void setTendersJson(String tendersJson) { this.tendersJson = tendersJson; }
    public void setPriceListId(String priceListId) { this.priceListId = priceListId; }
}
