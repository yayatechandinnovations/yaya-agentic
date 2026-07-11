package com.yayatechandinnovations.yayasales.customers;

import com.yayatechandinnovations.yayasales.platform.Address;
import com.yayatechandinnovations.yayasales.platform.Ids.AddressId;
import com.yayatechandinnovations.yayasales.platform.Ids.CustomerId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;

import java.time.Instant;
import java.util.List;

public record CustomerView(
        CustomerId id,
        StoreId store,
        String customerRef,
        String displayName,
        String email,
        String phone,
        String notes,
        List<AddressView> addresses,
        Instant createdAt) {

    public record AddressView(AddressId id, Kind kind, Address address) {}

    public enum Kind { SHIPPING, BILLING, OTHER }
}
