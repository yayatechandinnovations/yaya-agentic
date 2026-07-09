package com.yayatechandinnovations.yayasales.inventory.internal;

import com.yayatechandinnovations.yayasales.inventory.InventoryAdmin;
import com.yayatechandinnovations.yayasales.inventory.MovementKind;
import com.yayatechandinnovations.yayasales.inventory.MovementView;
import com.yayatechandinnovations.yayasales.inventory.Ref;
import com.yayatechandinnovations.yayasales.inventory.StockLevel;
import com.yayatechandinnovations.yayasales.platform.Ids.LocationId;
import com.yayatechandinnovations.yayasales.platform.Ids.MovementId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;
import com.yayatechandinnovations.yayasales.platform.money.Money;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import com.yayatechandinnovations.yayasales.stores.LocationView;
import com.yayatechandinnovations.yayasales.stores.StoreDirectory;
import com.yayatechandinnovations.yayasales.stores.StorePolicies.BackorderPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class JpaInventoryAdmin implements InventoryAdmin {

    private final StockSummaryRepository summaries;
    private final StockMovementRepository movements;
    private final StoreDirectory stores;

    public JpaInventoryAdmin(StockSummaryRepository summaries,
                             StockMovementRepository movements,
                             StoreDirectory stores) {
        this.summaries = summaries;
        this.movements = movements;
        this.stores = stores;
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockLevel> levels(StoreId store, Optional<LocationId> location, boolean lowOnly) {
        List<StockSummaryEntity> rows = location
                .map(loc -> summaries.findByStoreIdAndIdLocationId(store.value(), loc.value()))
                .orElseGet(() -> summaries.findByStoreId(store.value()));
        return rows.stream()
                .map(JpaInventoryAdmin::toLevel)
                .filter(level -> !lowOnly || level.low())
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StockLevel> level(VariantId sku, LocationId location) {
        return summaries.findById(new StockSummaryEntity.Key(sku.value(), location.value()))
                .map(JpaInventoryAdmin::toLevel);
    }

    @Override
    @Transactional(readOnly = true)
    public long availableForSale(StoreId store, VariantId sku) {
        Set<String> sellable = stores.locationsOf(store).stream()
                .filter(LocationView::sellable)
                .map(l -> l.id().value())
                .collect(Collectors.toSet());
        return summaries.findByStoreIdAndIdSkuId(store.value(), sku.value()).stream()
                .filter(s -> sellable.contains(s.getId().locationId()))
                .mapToLong(StockSummaryEntity::available)
                .sum();
    }

    @Override
    @Transactional(readOnly = true)
    public List<MovementView> ledger(VariantId sku, Optional<LocationId> location) {
        List<StockMovementEntity> rows = location
                .map(loc -> movements.findBySkuIdAndLocationIdOrderByRecordedAtDesc(sku.value(), loc.value()))
                .orElseGet(() -> movements.findBySkuIdOrderByRecordedAtDesc(sku.value()));
        return rows.stream().map(JpaInventoryAdmin::toView).toList();
    }

    @Override
    @Transactional
    public void setReorder(VariantId sku, LocationId location, int point, int qty) {
        if (point < 0 || qty < 0) {
            throw new IllegalArgumentException("reorder point/qty must be non-negative");
        }
        locked(sku, location).setReorder(point, qty);
    }

    @Override
    @Transactional
    public void setBackorderPolicy(VariantId sku, LocationId location, BackorderPolicy policy) {
        if (policy == null) throw new IllegalArgumentException("policy is required");
        locked(sku, location).setBackorderPolicy(policy.name());
    }

    private StockSummaryEntity locked(VariantId sku, LocationId location) {
        return summaries.lockById(new StockSummaryEntity.Key(sku.value(), location.value()))
                .orElseThrow(() -> new ApiErrors.NotFoundException(
                        "stock summary", sku.value() + "@" + location.value()));
    }

    private static StockLevel toLevel(StockSummaryEntity e) {
        return new StockLevel(
                new VariantId(e.getId().skuId()),
                new LocationId(e.getId().locationId()),
                new StoreId(e.getStoreId()),
                e.getOnHand(), e.getReserved(),
                e.getAvgCostMinor() == null ? null : new Money(e.getAvgCostMinor(), e.getCurrency()),
                e.getReorderPoint(), e.getReorderQty(),
                BackorderPolicy.valueOf(e.getBackorderPolicy()));
    }

    private static MovementView toView(StockMovementEntity m) {
        return new MovementView(
                new MovementId(m.getId()),
                new VariantId(m.getSkuId()),
                new LocationId(m.getLocationId()),
                m.getQtyDelta(),
                MovementKind.valueOf(m.getKind()),
                m.getUnitCostMinor() == null ? null : new Money(m.getUnitCostMinor(), m.getCurrency()),
                Ref.RefType.valueOf(m.getRefType()),
                m.getRefId(), m.getReason(), m.getOccurredAt(), m.getRecordedAt());
    }
}
