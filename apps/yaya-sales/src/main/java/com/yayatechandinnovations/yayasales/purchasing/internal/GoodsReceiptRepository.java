package com.yayatechandinnovations.yayasales.purchasing.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GoodsReceiptRepository extends JpaRepository<GoodsReceiptEntity, String> {

    List<GoodsReceiptEntity> findByPurchaseOrderIdOrderByReceivedAtAsc(String purchaseOrderId);
}
