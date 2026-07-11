package com.yayatechandinnovations.yayasales.customers.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CustomerRepository extends JpaRepository<CustomerEntity, String> {

    List<CustomerEntity> findByStoreIdOrderByDisplayNameAsc(String storeId);

    Optional<CustomerEntity> findByStoreIdAndCustomerRef(String storeId, String customerRef);
}
