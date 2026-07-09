package com.yayatechandinnovations.yayasales.orders.internal;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<OrderEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OrderEntity o where o.id = :id")
    Optional<OrderEntity> lockById(@Param("id") String id);

    List<OrderEntity> findByStoreIdOrderByCreatedAtDesc(String storeId);

    List<OrderEntity> findByStoreIdAndStatusOrderByCreatedAtDesc(String storeId, String status);
}
