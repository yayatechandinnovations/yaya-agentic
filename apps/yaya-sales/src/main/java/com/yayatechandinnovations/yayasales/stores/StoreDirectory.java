package com.yayatechandinnovations.yayasales.stores;

import com.yayatechandinnovations.yayasales.platform.Ids.LocationId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.TenantRef;

import java.util.List;
import java.util.Optional;

/**
 * The read API sibling modules consume (inventory derives the store and
 * policies for a location; catalog/pricing scope by store). In-process module
 * API — never a table read across module boundaries.
 */
public interface StoreDirectory {

    Optional<StoreView> store(StoreId id);

    Optional<LocationView> location(LocationId id);

    List<LocationView> locationsOf(StoreId store);

    List<StoreView> storesOf(TenantRef tenant);

    /** Store policies with defaults applied — never empty for an existing store. */
    StorePolicies policiesOf(StoreId store);
}
