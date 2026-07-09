package com.yayatechandinnovations.yayasales.fulfillment.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "delivery_methods")
public class DeliveryMethodEntity {

    @Id
    private String id;

    @Column(name = "store_id", nullable = false)
    private String storeId;

    @Column(nullable = false)
    private String kind;

    @Column(nullable = false)
    private String label;

    @Column(nullable = false)
    private boolean active;

    protected DeliveryMethodEntity() {}

    public DeliveryMethodEntity(String id, String storeId, String kind, String label) {
        this.id = id;
        this.storeId = storeId;
        this.kind = kind;
        this.label = label;
        this.active = true;
    }

    public String getId() { return id; }
    public String getStoreId() { return storeId; }
    public String getKind() { return kind; }
    public String getLabel() { return label; }
    public boolean isActive() { return active; }

    public void setActive(boolean active) { this.active = active; }
}
