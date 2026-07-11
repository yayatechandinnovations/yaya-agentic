package com.yayatechandinnovations.yayasales.stores;

import com.yayatechandinnovations.yayasales.platform.Address;
import com.yayatechandinnovations.yayasales.platform.Ids.LocationId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.TenantRef;

/**
 * Store lifecycle (admin-facing). Creating a store seeds one sellable default
 * location so a simple store needs no extra setup (§6.1 v1 seeding rule;
 * channels/regions seeding joins in S2).
 */
public interface StoreService {

    StoreView create(TenantRef tenant, String displayName, String defaultCurrency, StorePolicies policies);

    StoreView updatePolicies(StoreId id, StorePolicies policies);

    LocationView addLocation(StoreId store, String label, LocationView.Kind kind,
                             boolean sellable, Address address);

    LocationView updateLocation(LocationId id, String label, LocationView.Kind kind,
                                Boolean sellable, Address address);
}
