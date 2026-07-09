package com.yayatechandinnovations.yayasales.inventory.internal;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StockSummaryRepository
        extends JpaRepository<StockSummaryEntity, StockSummaryEntity.Key> {

    /** The §5.2 guard: concurrent reservers serialize on the summary row. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StockSummaryEntity s where s.id = :id")
    Optional<StockSummaryEntity> lockById(@Param("id") StockSummaryEntity.Key id);

    List<StockSummaryEntity> findByStoreId(String storeId);

    List<StockSummaryEntity> findByStoreIdAndIdLocationId(String storeId, String locationId);

    List<StockSummaryEntity> findByStoreIdAndIdSkuId(String storeId, String skuId);
}
