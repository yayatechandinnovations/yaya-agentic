package com.yayatechandinnovations.yayasales.platform.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyRepository
        extends JpaRepository<IdempotencyEntity, IdempotencyEntity.Key> {
}
