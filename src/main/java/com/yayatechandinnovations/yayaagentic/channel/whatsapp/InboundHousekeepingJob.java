package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.yayatechandinnovations.yayaagentic.persistence.ChannelAutoReplyRepository;
import com.yayatechandinnovations.yayaagentic.persistence.InboundMessageEntity;
import com.yayatechandinnovations.yayaagentic.persistence.InboundMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Two scheduled chores against {@code inbound_messages}:
 *
 * <ol>
 *   <li><b>Rescue.</b> Re-enqueue rows whose {@code processed_at} is null
 *       five minutes after {@code received_at} — they were ack'd by the
 *       webhook but never made it through the worker (process crash,
 *       executor saturation, transient error). The retry is idempotent
 *       because the worker checks {@code processed_at} on entry.</li>
 *   <li><b>Reap.</b> Delete rows older than the §16 replay-attack dedup
 *       window (24h). Keeping older rows would slowly bloat the table
 *       without adding security — Twilio's retry behaviour caps at 24h.</li>
 *   <li><b>Reap auto-reply claims.</b> Delete {@code channel_auto_replies}
 *       rows older than 30 days: a claim only matters inside its 24 h
 *       window, after which the row is dead weight for a number that may
 *       never write again.</li>
 * </ol>
 */
@Component
public class InboundHousekeepingJob {

    private static final Logger log = LoggerFactory.getLogger(InboundHousekeepingJob.class);

    private final InboundMessageRepository inbound;
    private final ChannelAutoReplyRepository autoReplies;
    private final InboundDispatchWorker worker;
    private final boolean enabled;
    private final int rescueAfterMinutes;
    private final int reapAfterHours;

    public InboundHousekeepingJob(InboundMessageRepository inbound,
                                  ChannelAutoReplyRepository autoReplies,
                                  InboundDispatchWorker worker,
                                  @Value("${yaya.agentic.channels.whatsapp.housekeeping.enabled:true}")
                                          boolean enabled,
                                  @Value("${yaya.agentic.channels.whatsapp.housekeeping.rescue-after-minutes:5}")
                                          int rescueAfterMinutes,
                                  @Value("${yaya.agentic.channels.whatsapp.housekeeping.reap-after-hours:24}")
                                          int reapAfterHours) {
        this.inbound = inbound;
        this.autoReplies = autoReplies;
        this.worker = worker;
        this.enabled = enabled;
        this.rescueAfterMinutes = rescueAfterMinutes;
        this.reapAfterHours = reapAfterHours;
    }

    @Scheduled(
            fixedDelayString = "${yaya.agentic.channels.whatsapp.housekeeping.fixed-delay-ms:120000}",
            initialDelayString = "${yaya.agentic.channels.whatsapp.housekeeping.initial-delay-ms:60000}"
    )
    public void runHousekeeping() {
        if (!enabled) return;
        rescueStuckRows();
        reapStaleRows();
        reapAutoReplyClaims();
    }

    @Transactional
    protected void rescueStuckRows() {
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC)
                .minusMinutes(rescueAfterMinutes);
        List<InboundMessageEntity> stuck =
                inbound.findTop100ByProcessedAtIsNullAndReceivedAtBeforeOrderByReceivedAtAsc(cutoff);
        if (stuck.isEmpty()) return;
        log.info("Inbound rescue: re-enqueueing {} stuck rows older than {}",
                stuck.size(), cutoff);
        for (InboundMessageEntity row : stuck) {
            try {
                worker.schedule(row.getMessageSid());
            } catch (RuntimeException e) {
                log.warn("Inbound rescue: re-enqueue failed for {}: {}",
                        row.getMessageSid(), e.getMessage());
            }
        }
    }

    @Transactional
    protected void reapStaleRows() {
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC)
                .minusHours(reapAfterHours);
        // Find the oldest processed rows in batches to avoid a giant delete
        // holding a long lock. The Top100 pagination keeps each pass quick.
        List<InboundMessageEntity> stale =
                inbound.findTop100ByProcessedAtIsNullAndReceivedAtBeforeOrderByReceivedAtAsc(
                        cutoff);
        // The rescue query returns processed_at IS NULL — for reap we'd
        // ideally want a "received_at < cutoff" query regardless of processed
        // state. Add a focused query rather than scan-everything.
        List<InboundMessageEntity> oldRows =
                inbound.findTop100ByReceivedAtBeforeOrderByReceivedAtAsc(cutoff);
        if (oldRows.isEmpty()) return;
        log.info("Inbound reap: deleting {} rows older than {}", oldRows.size(), cutoff);
        inbound.deleteAll(oldRows);
    }

    /** How long an auto-reply claim is kept past its 24 h window before the reaper drops it. */
    static final int AUTO_REPLY_RETENTION_DAYS = 30;

    @Transactional
    protected void reapAutoReplyClaims() {
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minusDays(AUTO_REPLY_RETENTION_DAYS);
        int deleted = autoReplies.deleteByRepliedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("Auto-reply reap: deleted {} claims older than {}", deleted, cutoff);
        }
    }
}
