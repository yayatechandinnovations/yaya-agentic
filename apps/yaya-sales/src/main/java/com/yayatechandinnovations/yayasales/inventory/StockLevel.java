package com.yayatechandinnovations.yayasales.inventory;

import com.yayatechandinnovations.yayasales.platform.Ids.LocationId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;
import com.yayatechandinnovations.yayasales.platform.money.Money;
import com.yayatechandinnovations.yayasales.stores.StorePolicies.BackorderPolicy;

/** §7.2 — the summary projection as read by admin/catalog. Shoppers see {@link #available()}. */
public record StockLevel(
        VariantId sku,
        LocationId location,
        StoreId store,
        long onHand,
        long reserved,
        Money avgCost,              // null until the first costed receipt
        int reorderPoint,
        int reorderQty,
        BackorderPolicy backorder) {

    public long available() {
        return onHand - reserved;
    }

    public boolean low() {
        return reorderPoint > 0 && available() <= reorderPoint;
    }
}
