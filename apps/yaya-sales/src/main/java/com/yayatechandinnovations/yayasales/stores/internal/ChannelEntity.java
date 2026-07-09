package com.yayatechandinnovations.yayasales.stores.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "sales_channels")
public class ChannelEntity {

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

    protected ChannelEntity() {}

    public ChannelEntity(String id, String storeId, String kind, String label) {
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
}
