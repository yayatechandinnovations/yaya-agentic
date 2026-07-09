package com.yayatechandinnovations.yayasales.returns.internal;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RmaRepository extends JpaRepository<RmaEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RmaEntity r where r.id = :id")
    Optional<RmaEntity> lockById(@Param("id") String id);

    List<RmaEntity> findByOrderId(String orderId);

    List<RmaEntity> findByStoreIdOrderByCreatedAtDesc(String storeId);

    List<RmaEntity> findByStoreIdAndStatusOrderByCreatedAtDesc(String storeId, String status);
}
