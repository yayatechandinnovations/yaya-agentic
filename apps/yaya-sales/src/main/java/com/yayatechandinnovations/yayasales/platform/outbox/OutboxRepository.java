package com.yayatechandinnovations.yayasales.platform.outbox;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEntity, Long> {

    List<OutboxEntity> findByStatusOrderByCreatedAtAsc(String status, Limit limit);
}
