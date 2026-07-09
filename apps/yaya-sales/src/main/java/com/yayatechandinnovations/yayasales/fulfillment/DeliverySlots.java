package com.yayatechandinnovations.yayasales.fulfillment;

import com.yayatechandinnovations.yayasales.platform.Ids.DeliveryMethodId;
import com.yayatechandinnovations.yayasales.platform.Ids.LocationId;
import com.yayatechandinnovations.yayasales.platform.Ids.OrderId;
import com.yayatechandinnovations.yayasales.platform.Ids.SlotId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;

import java.time.Instant;
import java.util.List;

/**
 * Decision 10 / §17 — capacity-limited delivery &amp; pickup windows. Booking
 * is a HARD hold under the same pessimistic-guard pattern as stock (§5.2):
 * concurrent checkouts on the last slot yield exactly one booking (§14 no slot
 * oversell). Book and release are idempotent per order, so a retried checkout
 * re-attaches and a double compensation releases once.
 */
public interface DeliverySlots {

    List<SlotView> available(StoreId store, DeliveryMethodId method, LocationId location,
                             Instant from, Instant to);

    /** Throws Conflict when the slot is full. Idempotent per (slot, order). */
    void book(SlotId slot, OrderId order);

    /** Gives the capacity back. Idempotent per (slot, order). */
    void release(SlotId slot, OrderId order);

    record SlotView(SlotId id, StoreId store, LocationId location, DeliveryMethodId method,
                    Instant startsAt, Instant endsAt, int capacity, int booked) {

        public boolean full() {
            return booked >= capacity;
        }
    }
}
