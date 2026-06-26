package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.yayatechandinnovations.yayaagentic.channel.OutboundResult;
import com.yayatechandinnovations.yayaagentic.persistence.OutboundMessageEntity;
import com.yayatechandinnovations.yayaagentic.persistence.OutboundMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * §10.3 outbound retry queue. Sweeps {@code outbound_messages} for rows
 * stuck in {@code QUEUED} state and re-calls {@code sendFreeform} after
 * exponential backoff. Caps at 3 attempts (1s → 8s → 64s, ±20% jitter);
 * after that the row flips to {@code FAILED} and stays there for operator
 * inspection.
 *
 * <p>Idempotency: re-sending an outbound creates a NEW Twilio message sid
 * (we don't reuse the synthetic {@code PNDF_...} sid). The original row is
 * updated to record the real sid + SENT status; this means the PK changes
 * across the retry, so we delete the synthetic row and insert a new one.
 */
@Component
public class OutboundRetryJob {

    private static final Logger log = LoggerFactory.getLogger(OutboundRetryJob.class);

    /** Per §10.3 — base unit of backoff. */
    private static final Duration BASE_BACKOFF = Duration.ofSeconds(1);
    /** Maximum retries before the row is marked FAILED. */
    private static final int MAX_RETRIES = 3;

    private final OutboundDispatcher dispatcher;
    private final OutboundMessageRepository outbound;
    private final boolean enabled;

    public OutboundRetryJob(OutboundDispatcher dispatcher,
                            OutboundMessageRepository outbound,
                            @Value("${yaya.agentic.channels.whatsapp.retry.enabled:true}") boolean enabled) {
        this.dispatcher = dispatcher;
        this.outbound = outbound;
        this.enabled = enabled;
    }

    @Scheduled(
            fixedDelayString = "${yaya.agentic.channels.whatsapp.retry.fixed-delay-ms:30000}",
            initialDelayString = "${yaya.agentic.channels.whatsapp.retry.initial-delay-ms:60000}"
    )
    public void sweep() {
        if (!enabled) return;
        List<OutboundMessageEntity> ready =
                outbound.findTop50ByStatusAndRetryCountLessThanOrderBySentAtAsc(
                        "QUEUED", MAX_RETRIES);
        if (ready.isEmpty()) return;

        Instant now = Instant.now();
        for (OutboundMessageEntity row : ready) {
            Instant nextAttemptAt = backoffFor(row);
            if (nextAttemptAt.isAfter(now)) continue;     // not yet
            try {
                attempt(row);
            } catch (RuntimeException e) {
                log.warn("Retry attempt threw for outbound row {}: {}",
                        row.getMessageSid(), e.getMessage());
            }
        }
    }

    @Transactional
    protected void attempt(OutboundMessageEntity row) {
        OutboundResult result = dispatcher.resend(row);
        if (result instanceof OutboundResult.Sent sent) {
            // Migrate the row to its real Twilio sid + SENT.
            OutboundMessageEntity replacement = new OutboundMessageEntity(
                    sent.messageSid(),
                    row.getTenantId(),
                    row.getSenderId(),
                    row.getEndUserPhone(),
                    row.getSessionId(),
                    row.getBody());
            replacement.setStatus("SENT");
            replacement.setRetryCount(row.getRetryCount() + 1);
            outbound.delete(row);
            outbound.save(replacement);
            return;
        }
        if (result instanceof OutboundResult.ProviderFailure f) {
            row.setRetryCount(row.getRetryCount() + 1);
            row.setFailureCode(f.code());
            row.setFailureMessage(f.message());
            if (!f.retriable() || row.getRetryCount() >= MAX_RETRIES) {
                row.setStatus("FAILED");
                row.setFailedAt(OffsetDateTime.now(ZoneOffset.UTC));
            }
            outbound.save(row);
        }
    }

    /** Exponential backoff with 20% jitter: 1s, 8s, 64s, … */
    private Instant backoffFor(OutboundMessageEntity row) {
        long baseSeconds = (long) Math.pow(8, row.getRetryCount());
        long jitterMillis = (long) (baseSeconds * 1000 * 0.2
                * (ThreadLocalRandom.current().nextDouble() - 0.5));
        return row.getSentAt() == null
                ? Instant.now()
                : row.getSentAt().toInstant()
                        .plus(Duration.ofSeconds(baseSeconds))
                        .plusMillis(jitterMillis);
    }
}
