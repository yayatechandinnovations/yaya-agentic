package com.yayatechandinnovations.yayasales.orders;

import com.yayatechandinnovations.yayasales.platform.Address;
import com.yayatechandinnovations.yayasales.platform.Ids.CarrierId;
import com.yayatechandinnovations.yayasales.platform.Ids.DeliveryMethodId;
import com.yayatechandinnovations.yayasales.platform.Ids.OrderId;
import com.yayatechandinnovations.yayasales.platform.Ids.RegionId;
import com.yayatechandinnovations.yayasales.platform.Ids.SlotId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;
import com.yayatechandinnovations.yayasales.platform.money.Money;

import java.time.Instant;
import java.util.List;

/**
 * The delivery seam the checkout saga calls (§8.2 steps 1/2a-b) — declared in
 * the orders module API and implemented by fulfillment, so the module graph
 * stays acyclic: fulfillment consumes orders (what to ship), never the other
 * way around. Everything here speaks platform types only.
 */
public interface CheckoutDelivery {

    /**
     * Step 1 (decision 9): normalize + required fields + zone match. An
     * unservable or malformed ship-to throws Unprocessable — before any
     * reservation firms or tender begins (§14 address gate).
     */
    Address validate(StoreId store, RegionId region, Address shipTo);

    /**
     * Step 2a: price the CHOSEN method for this destination and cart — the
     * result's price becomes the pricing context's shipping (§8.4). Throws
     * Unprocessable when the method isn't offered for the address.
     */
    Quote quote(StoreId store, RegionId region, Address shipTo, DeliveryMethodId method,
                List<QuoteLine> lines, Money goodsValue);

    /** Step 2b (decision 10): hard-capacity booking — §14 no slot oversell. Idempotent per order. */
    void bookSlot(SlotId slot, OrderId order);

    /** Compensation / cancel: give the capacity back. Idempotent per order. */
    void releaseSlot(SlotId slot, OrderId order);

    record QuoteLine(VariantId sku, long qty) {}

    /** The snapshot-able choice: what it costs and when it arrives (§8.4). */
    record Quote(DeliveryMethodId method, String kind, String label, Money price,
                 Instant promisedFrom, Instant promisedTo, CarrierId carrier, String serviceLevel) {}
}
