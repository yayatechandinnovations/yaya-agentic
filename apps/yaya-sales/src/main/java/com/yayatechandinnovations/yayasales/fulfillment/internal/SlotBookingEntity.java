package com.yayatechandinnovations.yayasales.fulfillment.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * Which order holds which slot — the row that makes book/release idempotent
 * per order (a retried checkout re-attaches; a double compensation releases
 * exactly once).
 */
@Entity
@Table(name = "delivery_slot_bookings")
public class SlotBookingEntity {

    @EmbeddedId
    private Key id;

    @Column(name = "booked_at", nullable = false)
    private Instant bookedAt;

    protected SlotBookingEntity() {}

    public SlotBookingEntity(Key id, Instant bookedAt) {
        this.id = id;
        this.bookedAt = bookedAt;
    }

    public Key getId() { return id; }
    public Instant getBookedAt() { return bookedAt; }

    @Embeddable
    public record Key(@Column(name = "slot_id") String slotId,
                      @Column(name = "order_id") String orderId) implements Serializable {

        public Key {
            Objects.requireNonNull(slotId);
            Objects.requireNonNull(orderId);
        }
    }
}
