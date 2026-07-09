package com.yayatechandinnovations.yayasales.inventory.internal;

import com.yayatechandinnovations.yayasales.inventory.InventoryLedger;
import com.yayatechandinnovations.yayasales.inventory.MovementKind;
import com.yayatechandinnovations.yayasales.inventory.Ref;
import com.yayatechandinnovations.yayasales.inventory.ReservationKind;
import com.yayatechandinnovations.yayasales.inventory.ReserveResult;
import com.yayatechandinnovations.yayasales.platform.Ids;
import com.yayatechandinnovations.yayasales.platform.Ids.LocationId;
import com.yayatechandinnovations.yayasales.platform.Ids.MovementId;
import com.yayatechandinnovations.yayasales.platform.Ids.ReservationId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;
import com.yayatechandinnovations.yayasales.platform.money.Money;
import com.yayatechandinnovations.yayasales.platform.outbox.Outbox;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import com.yayatechandinnovations.yayasales.stores.LocationView;
import com.yayatechandinnovations.yayasales.stores.StoreDirectory;
import com.yayatechandinnovations.yayasales.stores.StorePolicies.BackorderPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The guarded stock engine (§5.1–§5.3, §7). Every public method is one
 * transaction; every quantity change writes a movement AND updates the summary
 * in that same transaction; reservations serialize on the pessimistically
 * locked summary row so the last unit is sold exactly once.
 */
@Service
public class JpaInventoryLedger implements InventoryLedger {

    private final StockSummaryRepository summaries;
    private final StockMovementRepository movements;
    private final ReservationRepository reservations;
    private final StoreDirectory stores;
    private final Outbox outbox;

    public JpaInventoryLedger(StockSummaryRepository summaries,
                              StockMovementRepository movements,
                              ReservationRepository reservations,
                              StoreDirectory stores,
                              Outbox outbox) {
        this.summaries = summaries;
        this.movements = movements;
        this.reservations = reservations;
        this.stores = stores;
        this.outbox = outbox;
    }

    // ── reserve / revalidate / commit / release (§5.2, §5.3, §7.3) ──────────

    @Override
    @Transactional
    public ReserveResult reserve(VariantId sku, LocationId location, long qty,
                                 ReservationKind kind, Ref ref, Duration ttl) {
        requirePositive(qty);
        LocationView loc = sellableLocation(location);
        StockSummaryEntity summary = lockOrCreate(sku, location, loc.store());

        long availableBefore = summary.available();
        BackorderPolicy policy = BackorderPolicy.valueOf(summary.getBackorderPolicy());
        if (qty > availableBefore && policy == BackorderPolicy.DENY) {
            return new ReserveResult.Insufficient(Math.max(availableBefore, 0));
        }

        Instant expiresAt = Instant.now().plus(ttl);
        ReservationEntity held = new ReservationEntity(Ids.newId(), loc.store().value(),
                sku.value(), location.value(), qty, kind.name(),
                ref != null && ref.type() == Ref.RefType.CART ? ref.id() : null,
                ref != null && ref.type() == Ref.RefType.ORDER ? ref.id() : null,
                expiresAt, Instant.now());
        reservations.save(held);
        summary.addReserved(qty);
        maybeEmitStockLow(summary, availableBefore);
        return new ReserveResult.Held(new ReservationId(held.getId()), expiresAt);
    }

    @Override
    @Transactional
    public ReserveResult revalidate(ReservationId id, Duration firmTtl) {
        ReservationEntity r = reservations.lockById(id.value())
                .orElseThrow(() -> new ApiErrors.NotFoundException("reservation", id.value()));
        Instant now = Instant.now();

        if (r.held() && !r.lapsed(now)) {
            // Fresh hold stands: promote to FIRM with the checkout TTL (§8.2 step 2).
            r.firmUp(now.plus(firmTtl));
            return new ReserveResult.Held(new ReservationId(r.getId()), r.getExpiresAt());
        }

        // Lapsed (or already swept/released): return its units if still held,
        // then re-reserve against LIVE availability — or fail the line.
        if (r.held()) {
            lockedSummaryOf(r).drainReserved(r.getQty());
            r.markExpired();
        }
        if ("COMMITTED".equals(r.getState())) {
            throw new ApiErrors.ConflictException("reservation already committed: " + id.value());
        }
        Ref ref = r.getOrderId() != null ? Ref.order(r.getOrderId())
                : r.getCartId() != null ? Ref.cart(r.getCartId()) : null;
        return reserve(new VariantId(r.getSkuId()), new LocationId(r.getLocationId()),
                r.getQty(), ReservationKind.FIRM, ref, firmTtl);
    }

    @Override
    @Transactional
    public MovementId commit(ReservationId id) {
        ReservationEntity r = reservations.lockById(id.value())
                .orElseThrow(() -> new ApiErrors.NotFoundException("reservation", id.value()));
        if ("COMMITTED".equals(r.getState())) {
            // Idempotent: a retried settle commits exactly once (§14 COD single-settle).
            return findCommitMovement(r);
        }
        if (!r.held()) {
            throw new ApiErrors.ConflictException(
                    "reservation " + id.value() + " is " + r.getState() + " — cannot commit");
        }

        StockSummaryEntity summary = lockedSummaryOf(r);
        long availableBefore = summary.available();
        summary.drainReserved(r.getQty());
        summary.applyDelta(-r.getQty());
        r.markCommitted();

        // COGS drawn at the current moving-average cost (§7.5).
        StockMovementEntity sale = new StockMovementEntity(Ids.newId(), r.getStoreId(),
                r.getSkuId(), r.getLocationId(), -r.getQty(), MovementKind.SALE.name(),
                summary.getAvgCostMinor(), summary.getCurrency(),
                r.getOrderId() != null ? Ref.RefType.ORDER.name() : Ref.RefType.CART.name(),
                r.getOrderId() != null ? r.getOrderId() : r.getCartId(),
                null, Instant.now());
        movements.save(sale);
        maybeEmitStockLow(summary, availableBefore);
        return new MovementId(sale.getId());
    }

    @Override
    @Transactional
    public void release(ReservationId id) {
        ReservationEntity r = reservations.lockById(id.value())
                .orElseThrow(() -> new ApiErrors.NotFoundException("reservation", id.value()));
        if (!r.held()) {
            return;   // idempotent: released/expired/committed holds have nothing to give back
        }
        lockedSummaryOf(r).drainReserved(r.getQty());
        r.markReleased();
    }

    // ── the non-sale ways stock moves (§7.3) ─────────────────────────────────

    @Override
    @Transactional
    public MovementId receive(VariantId sku, LocationId location, long qty, Money unitCost, Ref ref) {
        requirePositive(qty);
        if (unitCost == null) {
            throw new IllegalArgumentException("unitCost is required on a receipt — it drives valuation");
        }
        LocationView loc = existingLocation(location);
        StockSummaryEntity summary = lockOrCreate(sku, location, loc.store());
        if (summary.getCurrency() != null && !summary.getCurrency().equals(unitCost.currency())) {
            throw new ApiErrors.UnprocessableException("receipt currency " + unitCost.currency()
                    + " differs from the SKU's costing currency " + summary.getCurrency());
        }

        summary.absorbCost(qty, unitCost.amountMinor(), unitCost.currency());
        summary.applyDelta(qty);

        StockMovementEntity receipt = new StockMovementEntity(Ids.newId(), loc.store().value(),
                sku.value(), location.value(), qty, MovementKind.RECEIPT.name(),
                unitCost.amountMinor(), unitCost.currency(),
                refType(ref, Ref.RefType.PURCHASE_ORDER), refId(ref), null, Instant.now());
        movements.save(receipt);

        outbox.append(loc.store(), "stock", sku.value(), "stock.received",
                "{\"sku\":\"" + sku.value() + "\",\"location\":\"" + location.value()
                        + "\",\"qty\":" + qty + "}");
        return new MovementId(receipt.getId());
    }

    @Override
    @Transactional
    public MovementId restock(VariantId sku, LocationId location, long qty, Ref ref) {
        requirePositive(qty);
        LocationView loc = existingLocation(location);
        StockSummaryEntity summary = lockOrCreate(sku, location, loc.store());
        summary.applyDelta(qty);

        StockMovementEntity movement = new StockMovementEntity(Ids.newId(), loc.store().value(),
                sku.value(), location.value(), qty, MovementKind.RETURN.name(),
                null, null, refType(ref, Ref.RefType.RMA), refId(ref), null, Instant.now());
        movements.save(movement);
        return new MovementId(movement.getId());
    }

    @Override
    @Transactional
    public MovementId adjust(VariantId sku, LocationId location, long delta, String reason) {
        if (delta == 0) {
            throw new IllegalArgumentException("adjustment delta must be non-zero");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("an adjustment requires a reason — the ledger explains every unit");
        }
        LocationView loc = existingLocation(location);
        StockSummaryEntity summary = lockOrCreate(sku, location, loc.store());
        long availableBefore = summary.available();
        summary.applyDelta(delta);

        StockMovementEntity movement = new StockMovementEntity(Ids.newId(), loc.store().value(),
                sku.value(), location.value(), delta, MovementKind.ADJUSTMENT.name(),
                null, null, Ref.RefType.ADJUSTMENT.name(), null, reason, Instant.now());
        movements.save(movement);
        maybeEmitStockLow(summary, availableBefore);
        return new MovementId(movement.getId());
    }

    @Override
    @Transactional
    public List<MovementId> transfer(VariantId sku, LocationId from, LocationId to, long qty) {
        requirePositive(qty);
        if (from.equals(to)) {
            throw new IllegalArgumentException("transfer requires two distinct locations");
        }
        LocationView fromLoc = existingLocation(from);
        LocationView toLoc = existingLocation(to);
        if (!fromLoc.store().equals(toLoc.store())) {
            throw new ApiErrors.UnprocessableException("transfers cannot cross stores");
        }

        // Deadlock avoidance: lock the two summary rows in a stable global order.
        List<LocationId> order = List.of(from, to).stream()
                .sorted(Comparator.comparing(LocationId::value)).toList();
        StockSummaryEntity first = lockOrCreate(sku, order.get(0), fromLoc.store());
        StockSummaryEntity second = lockOrCreate(sku, order.get(1), fromLoc.store());
        StockSummaryEntity source = first.getId().locationId().equals(from.value()) ? first : second;
        StockSummaryEntity dest = source == first ? second : first;

        if (source.available() < qty) {
            throw new ApiErrors.ConflictException("insufficient available stock to transfer: "
                    + source.available() + " < " + qty);
        }

        String transferRef = Ids.newId();
        Instant now = Instant.now();
        Long unitCost = source.getAvgCostMinor();
        String currency = source.getCurrency();

        source.applyDelta(-qty);
        StockMovementEntity out = new StockMovementEntity(Ids.newId(), fromLoc.store().value(),
                sku.value(), from.value(), -qty, MovementKind.TRANSFER_OUT.name(),
                unitCost, currency, Ref.RefType.TRANSFER.name(), transferRef, null, now);
        movements.save(out);

        // Arriving units carry the source's cost into the destination's average.
        if (unitCost != null) {
            dest.absorbCost(qty, unitCost, currency);
        }
        dest.applyDelta(qty);
        StockMovementEntity in = new StockMovementEntity(Ids.newId(), fromLoc.store().value(),
                sku.value(), to.value(), qty, MovementKind.TRANSFER_IN.name(),
                unitCost, currency, Ref.RefType.TRANSFER.name(), transferRef, null, now);
        movements.save(in);

        return List.of(new MovementId(out.getId()), new MovementId(in.getId()));
    }

    // ── internals ────────────────────────────────────────────────────────────

    private StockSummaryEntity lockOrCreate(VariantId sku, LocationId location, StoreId store) {
        StockSummaryEntity.Key key = new StockSummaryEntity.Key(sku.value(), location.value());
        return summaries.lockById(key).orElseGet(() -> {
            BackorderPolicy storeDefault = stores.policiesOf(store).backorder();
            return summaries.save(new StockSummaryEntity(key, store.value(), storeDefault.name()));
        });
    }

    private StockSummaryEntity lockedSummaryOf(ReservationEntity r) {
        return summaries.lockById(new StockSummaryEntity.Key(r.getSkuId(), r.getLocationId()))
                .orElseThrow(() -> new IllegalStateException(
                        "summary row missing for held reservation " + r.getId()));
    }

    private LocationView sellableLocation(LocationId id) {
        LocationView loc = existingLocation(id);
        if (!loc.sellable()) {
            throw new ApiErrors.UnprocessableException("location is not sellable: " + id.value());
        }
        return loc;
    }

    private LocationView existingLocation(LocationId id) {
        return stores.location(id)
                .orElseThrow(() -> new ApiErrors.NotFoundException("location", id.value()));
    }

    private MovementId findCommitMovement(ReservationEntity r) {
        String ref = r.getOrderId() != null ? r.getOrderId() : r.getCartId();
        return movements.findBySkuIdAndLocationIdOrderByRecordedAtDesc(r.getSkuId(), r.getLocationId())
                .stream()
                .filter(m -> MovementKind.SALE.name().equals(m.getKind()))
                .filter(m -> ref == null || ref.equals(m.getRefId()))
                .findFirst()
                .map(m -> new MovementId(m.getId()))
                .orElseThrow(() -> new IllegalStateException(
                        "committed reservation " + r.getId() + " has no SALE movement — ledger drift"));
    }

    private void maybeEmitStockLow(StockSummaryEntity summary, long availableBefore) {
        int point = summary.getReorderPoint();
        if (point > 0 && summary.available() <= point && availableBefore > point) {
            outbox.append(new StoreId(summary.getStoreId()), "stock", summary.getId().skuId(),
                    "stock.low", "{\"sku\":\"" + summary.getId().skuId()
                            + "\",\"location\":\"" + summary.getId().locationId()
                            + "\",\"available\":" + summary.available()
                            + ",\"reorderPoint\":" + point
                            + ",\"reorderQty\":" + summary.getReorderQty() + "}");
        }
    }

    private static void requirePositive(long qty) {
        if (qty <= 0) throw new IllegalArgumentException("qty must be positive, got " + qty);
    }

    private static String refType(Ref ref, Ref.RefType fallback) {
        return (ref == null ? fallback : ref.type()).name();
    }

    private static String refId(Ref ref) {
        return ref == null ? null : ref.id();
    }
}
