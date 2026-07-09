package com.yayatechandinnovations.yayasales.fulfillment.internal;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DeliverySlotRepository extends JpaRepository<DeliverySlotEntity, String> {

    /** The §5.2-style guard: concurrent bookers serialize on the slot row. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from DeliverySlotEntity s where s.id = :id")
    Optional<DeliverySlotEntity> lockById(@Param("id") String id);

    List<DeliverySlotEntity> findByStoreIdAndMethodIdAndLocationIdAndStartsAtGreaterThanEqualAndEndsAtLessThanEqualOrderByStartsAtAsc(
            String storeId, String methodId, String locationId, Instant from, Instant to);
}
