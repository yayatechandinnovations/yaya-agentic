package com.yayatechandinnovations.yayasales.payments.internal;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TenderRepository extends JpaRepository<OrderTenderEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from OrderTenderEntity t where t.id = :id")
    Optional<OrderTenderEntity> lockById(@Param("id") String id);

    List<OrderTenderEntity> findByOrderIdOrderByCreatedAtAsc(String orderId);

    Optional<OrderTenderEntity> findFirstByOrderIdAndKindAndStatus(String orderId, String kind, String status);
}
