package com.yayatechandinnovations.yayasales.payments.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentTransactionRepository
        extends JpaRepository<PaymentTransactionEntity, String> {

    List<PaymentTransactionEntity> findByOrderIdOrderByCreatedAtAsc(String orderId);
}
