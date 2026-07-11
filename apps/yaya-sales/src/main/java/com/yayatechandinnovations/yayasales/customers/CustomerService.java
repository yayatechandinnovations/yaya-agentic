package com.yayatechandinnovations.yayasales.customers;

import com.yayatechandinnovations.yayasales.platform.Address;
import com.yayatechandinnovations.yayasales.platform.Ids.AddressId;
import com.yayatechandinnovations.yayasales.platform.Ids.CustomerId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;

import java.util.List;
import java.util.Optional;

/** Customer directory CRUD + lookup by the ref orders carry. */
public interface CustomerService {

    CustomerView create(StoreId store, CustomerDraft draft);

    CustomerView update(CustomerId id, CustomerDraft draft);

    void delete(CustomerId id);

    Optional<CustomerView> customer(CustomerId id);

    Optional<CustomerView> byRef(StoreId store, String customerRef);

    /** q matches name/email/phone/ref, case-insensitive; blank = everyone. */
    List<CustomerView> search(StoreId store, String q);

    CustomerView addAddress(CustomerId id, CustomerView.Kind kind, Address address);

    CustomerView updateAddress(CustomerId id, AddressId addressId,
                               CustomerView.Kind kind, Address address);

    CustomerView removeAddress(CustomerId id, AddressId addressId);

    record CustomerDraft(String customerRef, String displayName, String email,
                         String phone, String notes) {}
}
