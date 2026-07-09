package com.yayatechandinnovations.yayaidentity.platform.outbox;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class JpaOutboxRelay implements OutboxRelay {

    private final OutboxRepository repo;

    public JpaOutboxRelay(OutboxRepository repo) {
        this.repo = repo;
    }

    // Mandatory: the event must be written in the caller's transaction so it
    // commits atomically with the state change (or not at all).
    @Override
    @Transactional
    public void append(String aggregate, String aggregateId, String eventType, String payloadJson) {
        repo.save(new OutboxEntity(aggregate, aggregateId, eventType, payloadJson, Instant.now()));
    }
}
