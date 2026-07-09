package com.yayatechandinnovations.yayasales.fulfillment;

import com.yayatechandinnovations.yayasales.platform.Address;
import com.yayatechandinnovations.yayasales.platform.Ids.RegionId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;

/**
 * Decision 9 — pluggable ship-to validation at checkout step 1: normalize,
 * require line1/city/country, and match a shipping zone. Unservable or
 * malformed throws Unprocessable BEFORE any money moves or reservation firms
 * (§8.2, §14 address gate). External verification services slot in later
 * behind this same SPI.
 */
public interface AddressValidator {

    Address validate(StoreId store, RegionId region, Address address);
}
