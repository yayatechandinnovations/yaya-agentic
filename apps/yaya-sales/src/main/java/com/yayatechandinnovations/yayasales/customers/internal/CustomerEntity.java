package com.yayatechandinnovations.yayasales.customers.internal;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "customers")
public class CustomerEntity {

    @Id
    private String id;

    @Column(name = "store_id", nullable = false)
    private String storeId;

    @Column(name = "customer_ref")
    private String customerRef;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    private String email;

    private String phone;

    private String notes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    @OrderBy("createdAt asc")
    private List<CustomerAddressEntity> addresses = new ArrayList<>();

    protected CustomerEntity() {}

    public CustomerEntity(String id, String storeId, String displayName, Instant createdAt) {
        this.id = id;
        this.storeId = storeId;
        this.displayName = displayName;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getStoreId() { return storeId; }
    public String getCustomerRef() { return customerRef; }
    public String getDisplayName() { return displayName; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public String getNotes() { return notes; }
    public Instant getCreatedAt() { return createdAt; }
    public List<CustomerAddressEntity> getAddresses() { return addresses; }

    public void setCustomerRef(String customerRef) { this.customerRef = customerRef; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public void setEmail(String email) { this.email = email; }
    public void setPhone(String phone) { this.phone = phone; }
    public void setNotes(String notes) { this.notes = notes; }
}
