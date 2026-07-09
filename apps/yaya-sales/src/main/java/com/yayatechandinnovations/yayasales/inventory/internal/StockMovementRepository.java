package com.yayatechandinnovations.yayasales.inventory.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface StockMovementRepository extends JpaRepository<StockMovementEntity, String> {

    List<StockMovementEntity> findBySkuIdOrderByRecordedAtDesc(String skuId);

    List<StockMovementEntity> findBySkuIdAndLocationIdOrderByRecordedAtDesc(String skuId, String locationId);

    /** Ledger ≡ summary invariant check: on-hand IS the sum of movements (§5.1). */
    @Query("select coalesce(sum(m.qtyDelta), 0) from StockMovementEntity m "
            + "where m.skuId = :skuId and m.locationId = :locationId")
    long sumDeltas(@Param("skuId") String skuId, @Param("locationId") String locationId);
}
