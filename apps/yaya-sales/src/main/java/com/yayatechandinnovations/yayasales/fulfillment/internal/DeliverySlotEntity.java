package com.yayatechandinnovations.yayasales.fulfillment.internal;

import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "delivery_slots")
public class DeliverySlotEntity {

    @Id
    private String id;

    @Column(name = "store_id", nullable = false)
    private String storeId;

    @Column(name = "location_id", nullable = false)
    private String locationId;

    @Column(name = "method_id", nullable = false)
    private String methodId;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(nullable = false)
    private int capacity;

    @Column(nullable = false)
    private int booked;

    protected DeliverySlotEntity() {}

    public DeliverySlotEntity(String id, String storeId, String locationId, String methodId,
                              Instant startsAt, Instant endsAt, int capacity) {
        this.id = id;
        this.storeId = storeId;
        this.locationId = locationId;
        this.methodId = methodId;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.capacity = capacity;
        this.booked = 0;
    }

    public String getId() { return id; }
    public String getStoreId() { return storeId; }
    public String getLocationId() { return locationId; }
    public String getMethodId() { return methodId; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getEndsAt() { return endsAt; }
    public int getCapacity() { return capacity; }
    public int getBooked() { return booked; }

    /** Only ever called on a pessimistically locked row (§14 no slot oversell). */
    public void take() {
        if (booked >= capacity) {
            throw new ApiErrors.ConflictException("delivery slot " + id + " is fully booked");
        }
        booked++;
    }

    public void giveBack() {
        if (booked > 0) {
            booked--;
        }
    }
}
