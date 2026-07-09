package com.yayatechandinnovations.yayasales.inventory.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * §7.3: an abandoned cart returns its stock automatically. Releases HELD
 * reservations past {@code expires_at} (state → EXPIRED, reserved drained), so
 * {@code available()} recovers without anyone doing anything.
 */
@Service
public class ReservationSweeper {

    private static final Logger log = LoggerFactory.getLogger(ReservationSweeper.class);
    private static final int BATCH = 200;

    private final ReservationRepository reservations;
    private final StockSummaryRepository summaries;
    private final boolean enabled;

    public ReservationSweeper(ReservationRepository reservations,
                              StockSummaryRepository summaries,
                              @Value("${yaya.sales.inventory.sweep-enabled:true}") boolean enabled) {
        this.reservations = reservations;
        this.summaries = summaries;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${yaya.sales.inventory.sweep-interval-ms:30000}")
    public void poll() {
        if (enabled) {
            int swept = sweepExpired();
            if (swept > 0) {
                log.info("released {} expired stock reservations", swept);
            }
        }
    }

    /** One sweep pass; returns how many holds were released. Callable directly (tests, ops). */
    @Transactional
    public int sweepExpired() {
        List<ReservationEntity> lapsed =
                reservations.findByStateAndExpiresAtBefore("HELD", Instant.now(), Limit.of(BATCH));
        int swept = 0;
        for (ReservationEntity r : lapsed) {
            summaries.lockById(new StockSummaryEntity.Key(r.getSkuId(), r.getLocationId()))
                    .ifPresent(s -> s.drainReserved(r.getQty()));
            r.markExpired();
            swept++;
        }
        return swept;
    }
}
