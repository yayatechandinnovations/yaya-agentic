package com.yayatechandinnovations.yayasales.stores;

import com.yayatechandinnovations.yayasales.platform.Ids.LocationId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;

public record LocationView(
        LocationId id,
        StoreId store,
        String label,
        Kind kind,
        boolean sellable,
        int sortOrder) {

    public enum Kind { WAREHOUSE, SHOP, VIRTUAL }
}
