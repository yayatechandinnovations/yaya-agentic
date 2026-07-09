package com.yayatechandinnovations.yayasales.fulfillment;

/**
 * §8.7 — where the parcel is, independent of the order's status. Carrier
 * webhooks, merchant manual advances, and the courier surface all move it;
 * {@link #rank()} keeps the movement monotonic (a replayed or late event never
 * walks a shipment backwards). READY_FOR_PICKUP → PICKED_UP is the counter
 * path (with an OTP); the rest is the door path.
 */
public enum DeliveryStatus {

    LABEL_CREATED(0),
    READY_FOR_PICKUP(0),
    IN_TRANSIT(1),
    OUT_FOR_DELIVERY(2),
    DELIVERED(3),
    PICKED_UP(3),
    FAILED(3),
    RETURNED_TO_SENDER(4);

    private final int rank;

    DeliveryStatus(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    public boolean terminal() {
        return this == DELIVERED || this == PICKED_UP || this == RETURNED_TO_SENDER;
    }
}
