package com.yayatechandinnovations.yayasales.orders.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderEventRepository extends JpaRepository<OrderEventEntity, Long> {

    List<OrderEventEntity> findByOrderIdOrderByAtAscIdAsc(String orderId);
}
