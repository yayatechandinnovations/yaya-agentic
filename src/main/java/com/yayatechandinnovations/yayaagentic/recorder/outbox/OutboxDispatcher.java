package com.yayatechandinnovations.yayaagentic.recorder.outbox;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.RecorderOutboxEntity;
import com.yayatechandinnovations.yayaagentic.persistence.RecorderOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Drains {@code recorder_outbox} → registered sinks. M1 ships with zero
 * sinks; in that case the dispatcher runs but finds nothing to do, which
 * is the whole point — we don't have to repaint the hot path when M5
 * adds sinks. Backoff is a simple capped exponential.
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private static final int BATCH_SIZE = 50;
    private static final int MAX_ATTEMPTS = 8;
    private static final Duration BASE_BACKOFF = Duration.ofSeconds(2);

    private final RecorderOutboxRepository outbox;
    private final Map<String, RecorderSink> sinksById;

    public OutboxDispatcher(RecorderOutboxRepository outbox, List<RecorderSink> sinks) {
        this.outbox = outbox;
        this.sinksById = sinks.stream().collect(java.util.stream.Collectors.toMap(
                RecorderSink::id, Function.identity()));
    }

    @Scheduled(fixedDelayString = "${yaya.agentic.recorder.dispatcher-interval-ms:1000}")
    @Transactional
    public void dispatch() {
        if (sinksById.isEmpty()) return;

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<RecorderOutboxEntity> batch = outbox.findReadyForDispatch(now, PageRequest.of(0, BATCH_SIZE));
        for (RecorderOutboxEntity row : batch) {
            RecorderSink sink = sinksById.get(row.getSinkId());
            if (sink == null) {
                log.warn("Outbox row {} references unknown sink '{}' — leaving pending", row.getId(), row.getSinkId());
                continue;
            }
            try {
                sink.publish(new OutboxEvent(
                        OutboxEvent.Kind.TURN_RECORDED,        // M1: TURN is the only emitted kind today
                        new Ids.TenantId(row.getTenantId()),
                        row.getSessionId(),
                        row.getTurnId(),
                        row.getPayloadJson()));
                row.markSent(OffsetDateTime.now(ZoneOffset.UTC));
            } catch (Exception e) {
                if (row.getAttempts() + 1 >= MAX_ATTEMPTS) {
                    log.error("Outbox row {} sink={} failed after {} attempts — marking FAILED",
                            row.getId(), row.getSinkId(), row.getAttempts() + 1, e);
                    row.markFailed();
                } else {
                    Duration backoff = BASE_BACKOFF.multipliedBy(1L << Math.min(row.getAttempts(), 6));
                    row.scheduleRetry(OffsetDateTime.now(ZoneOffset.UTC).plus(backoff));
                    log.warn("Outbox row {} sink={} retry {} in {}", row.getId(), row.getSinkId(),
                            row.getAttempts(), backoff);
                }
            }
        }
    }
}
