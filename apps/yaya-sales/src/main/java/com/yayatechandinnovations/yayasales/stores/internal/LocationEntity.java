package com.yayatechandinnovations.yayasales.stores.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "locations")
public class LocationEntity {

    @Id
    private String id;

    @Column(name = "store_id", nullable = false)
    private String storeId;

    @Column(nullable = false)
    private String label;

    @Column(nullable = false)
    private String kind;

    @Column(nullable = false)
    private boolean sellable;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "address_json")
    private String addressJson;

    protected LocationEntity() {}

    public LocationEntity(String id, String storeId, String label, String kind,
                          boolean sellable, int sortOrder) {
        this.id = id;
        this.storeId = storeId;
        this.label = label;
        this.kind = kind;
        this.sellable = sellable;
        this.sortOrder = sortOrder;
    }

    public String getId() { return id; }
    public String getStoreId() { return storeId; }
    public String getLabel() { return label; }
    public String getKind() { return kind; }
    public boolean isSellable() { return sellable; }
    public int getSortOrder() { return sortOrder; }
    public String getAddressJson() { return addressJson; }

    public void setLabel(String label) { this.label = label; }
    public void setKind(String kind) { this.kind = kind; }
    public void setSellable(boolean sellable) { this.sellable = sellable; }
    public void setAddressJson(String addressJson) { this.addressJson = addressJson; }
}
