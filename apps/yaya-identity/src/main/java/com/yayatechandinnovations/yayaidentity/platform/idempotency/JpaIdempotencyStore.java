package com.yayatechandinnovations.yayaidentity.platform.idempotency;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
public class JpaIdempotencyStore implements IdempotencyStore {

    private final IdempotencyRepository repo;

    public JpaIdempotencyStore(IdempotencyRepository repo) {
        this.repo = repo;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> find(String scope, String key) {
        return repo.findById(new IdempotencyEntity.Key(scope, key))
                .map(IdempotencyEntity::getResponseJson);
    }

    @Override
    @Transactional
    public void put(String scope, String key, String responseJson) {
        repo.save(new IdempotencyEntity(
                new IdempotencyEntity.Key(scope, key), responseJson, Instant.now()));
    }
}
