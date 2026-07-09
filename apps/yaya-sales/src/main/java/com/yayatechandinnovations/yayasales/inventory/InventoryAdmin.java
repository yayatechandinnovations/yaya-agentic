package com.yayatechandinnovations.yayasales.inventory;

import com.yayatechandinnovations.yayasales.platform.Ids.LocationId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;
import com.yayatechandinnovations.yayasales.stores.StorePolicies.BackorderPolicy;

import java.util.List;
import java.util.Optional;

/**
 * Read + policy surface for the admin API (§10.1) and for catalog/search
 * availability display. All stock WRITES go through {@link InventoryLedger}.
 */
public interface InventoryAdmin {

    List<StockLevel> levels(StoreId store, Optional<LocationId> location, boolean lowOnly);

    Optional<StockLevel> level(VariantId sku, LocationId location);

    /** Availability summed across sellable locations (§7.4) — what a shopper is shown. */
    long availableForSale(StoreId store, VariantId sku);

    List<MovementView> ledger(VariantId sku, Optional<LocationId> location);

    void setReorder(VariantId sku, LocationId location, int point, int qty);

    void setBackorderPolicy(VariantId sku, LocationId location, BackorderPolicy policy);
}
