package com.yayatechandinnovations.yayasales.purchasing.internal;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrderEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PurchaseOrderEntity p where p.id = :id")
    Optional<PurchaseOrderEntity> lockById(@Param("id") String id);

    List<PurchaseOrderEntity> findByStoreIdOrderByCreatedAtDesc(String storeId);

    List<PurchaseOrderEntity> findByStoreIdAndStatusOrderByCreatedAtDesc(String storeId,
                                                                         String status);

    boolean existsBySupplierId(String supplierId);
}
