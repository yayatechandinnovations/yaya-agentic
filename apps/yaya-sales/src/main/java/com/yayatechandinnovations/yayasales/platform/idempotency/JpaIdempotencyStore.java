package com.yayatechandinnovations.yayasales.platform.idempotency;

import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
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
    public Optional<Stored> find(StoreId store, String key) {
        return repo.findById(new IdempotencyEntity.Key(store.value(), key))
                .map(e -> new Stored(e.getRequestHash(), e.getResponseJson()));
    }

    @Override
    @Transactional
    public void put(StoreId store, String key, String requestHash, String responseJson) {
        repo.save(new IdempotencyEntity(
                new IdempotencyEntity.Key(store.value(), key), requestHash, responseJson, Instant.now()));
    }
}
