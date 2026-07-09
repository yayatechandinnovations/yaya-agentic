package com.yayatechandinnovations.yayaidentity.platform.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyRepository
        extends JpaRepository<IdempotencyEntity, IdempotencyEntity.Key> {
}
