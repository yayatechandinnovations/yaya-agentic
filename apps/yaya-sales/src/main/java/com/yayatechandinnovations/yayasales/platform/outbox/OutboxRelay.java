package com.yayatechandinnovations.yayasales.platform.outbox;

import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Write + relay sides of the transactional outbox (design §5.6, §17). The
 * poller drains PENDING rows to every registered {@link OutboxPublisher}
 * at-least-once; a row is marked PUBLISHED only when all publishers accepted
 * it, so a crash mid-publish re-delivers rather than drops.
 */
@Service
public class OutboxRelay implements Outbox {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH = 100;

    private final OutboxRepository repo;
    private final List<OutboxPublisher> publishers;
    private final boolean pollEnabled;

    public OutboxRelay(OutboxRepository repo,
                       ObjectProvider<OutboxPublisher> publishers,
                       @Value("${yaya.sales.outbox.poll-enabled:true}") boolean pollEnabled) {
        this.repo = repo;
        // No publishers registered yet (pre-S2) is legal: events accumulate
        // PENDING and are relayed once a consumer exists — never dropped.
        this.publishers = publishers.orderedStream().toList();
        this.pollEnabled = pollEnabled;
    }

    // MANDATORY: the event must commit atomically with the state change it
    // describes — appending outside a transaction is a bug, not a convenience.
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(StoreId store, String aggregate, String aggregateId,
                       String eventType, String payloadJson) {
        repo.save(new OutboxEntity(store == null ? null : store.value(),
                aggregate, aggregateId, eventType, payloadJson, Instant.now()));
    }

    @Scheduled(fixedDelayString = "${yaya.sales.outbox.poll-interval-ms:2000}")
    public void poll() {
        if (pollEnabled) {
            publishPending();
        }
    }

    /** Drains PENDING rows; returns how many were published. Safe to call directly (tests, ops). */
    @Transactional
    public int publishPending() {
        // No consumers yet → rows stay PENDING; marking them PUBLISHED with
        // nobody delivered-to would silently lose events.
        if (publishers.isEmpty()) return 0;
        List<OutboxEntity> pending = repo.findByStatusOrderByCreatedAtAsc("PENDING", Limit.of(BATCH));
        int published = 0;
        for (OutboxEntity row : pending) {
            try {
                for (OutboxPublisher publisher : publishers) {
                    publisher.publish(new OutboxPublisher.OutboxEvent(
                            row.getId(), row.getStoreId(), row.getAggregate(),
                            row.getAggregateId(), row.getEventType(), row.getPayloadJson()));
                }
                row.markPublished(Instant.now());
                published++;
            } catch (Exception e) {
                row.markAttemptFailed();
                log.warn("outbox event {} ({}) failed publish attempt {}: {}",
                        row.getId(), row.getEventType(), row.getAttempts(), e.getMessage());
            }
        }
        return published;
    }
}
