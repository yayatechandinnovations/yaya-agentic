package com.yayatechandinnovations.yayasales.inventory;

import com.yayatechandinnovations.yayasales.platform.Ids.LocationId;
import com.yayatechandinnovations.yayasales.platform.Ids.MovementId;
import com.yayatechandinnovations.yayasales.platform.Ids.ReservationId;
import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;
import com.yayatechandinnovations.yayasales.platform.money.Money;

import java.time.Duration;
import java.util.List;

/**
 * The published inventory SPI (design §7.3, §17). WHEN to reserve/commit is
 * the caller's decision (cart, orders, fulfillment — per the store's deduction
 * point); this module guarantees each call is atomic, guarded against
 * oversell (§5.2), and explained by a ledger movement.
 */
public interface InventoryLedger {

    /** The §5.2 guarded path: at most {@code available()} can ever be held. */
    ReserveResult reserve(VariantId sku, LocationId location, long qty,
                          ReservationKind kind, Ref ref, Duration ttl);

    /**
     * Checkout revalidation (§5.3/§8.2): a live hold is promoted to FIRM with
     * the checkout TTL; a lapsed one is re-reserved against live availability
     * — or {@code Insufficient} if the stock is gone.
     */
    ReserveResult revalidate(ReservationId id, Duration firmTtl);

    /** Commit → one SALE movement (−qty, COGS at current avg cost). Idempotent on a committed hold. */
    MovementId commit(ReservationId id);

    /** Release the hold; availability recovers. Idempotent on a non-held reservation. */
    void release(ReservationId id);

    MovementId receive(VariantId sku, LocationId location, long qty, Money unitCost, Ref ref);

    MovementId restock(VariantId sku, LocationId location, long qty, Ref ref);

    MovementId adjust(VariantId sku, LocationId location, long delta, String reason);

    /** TRANSFER_OUT at {@code from} + TRANSFER_IN at {@code to}, atomically. */
    List<MovementId> transfer(VariantId sku, LocationId from, LocationId to, long qty);
}
