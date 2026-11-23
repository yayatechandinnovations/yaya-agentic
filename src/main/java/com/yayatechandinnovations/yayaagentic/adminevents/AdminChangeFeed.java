package com.yayatechandinnovations.yayaagentic.adminevents;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory, per-instance fan-out of {@link AdminChange}s with a bounded replay
 * buffer for reconnecting clients ({@code Last-Event-ID}). Event ids are
 * {@code <bootId>-<seq>}: an id from another boot, or one older than the
 * buffer, cannot be replayed faithfully, so the subscriber is told to reset
 * (refetch everything) instead of silently missing changes.
 */
@Component
public class AdminChangeFeed {

    static final int REPLAY_CAPACITY = 2000;

    /** Rapid repeat updates of one row (a session's last-activity, a progress counter) collapse to one. */
    static final Duration COALESCE_WINDOW = Duration.ofSeconds(1);

    private final Clock clock;
    private final String bootId;
    private final Deque<AdminChange> recent = new ArrayDeque<>();
    private final Map<String, Instant> lastUpdateByKey = new HashMap<>();
    private final Sinks.Many<AdminChange> sink = Sinks.many().multicast().directBestEffort();
    private long seq;

    public AdminChangeFeed() {
        this(Clock.systemUTC());
    }

    AdminChangeFeed(Clock clock) {
        this.clock = clock;
        this.bootId = Long.toString(clock.millis(), 36);
    }

    public synchronized void publish(String tenant, String kind, String id, Integer version, AdminChange.Op op) {
        Instant now = clock.instant();
        String key = tenant + '|' + kind + '|' + id;
        if (op == AdminChange.Op.UPDATED) {
            Instant last = lastUpdateByKey.get(key);
            if (last != null && Duration.between(last, now).compareTo(COALESCE_WINDOW) < 0) return;
        }
        lastUpdateByKey.put(key, now);
        if (lastUpdateByKey.size() > REPLAY_CAPACITY * 2) lastUpdateByKey.clear();

        long next = ++seq;
        AdminChange change = new AdminChange(bootId + '-' + next, next, tenant, kind, id, version, op, now);
        recent.addLast(change);
        if (recent.size() > REPLAY_CAPACITY) recent.removeFirst();
        sink.tryEmitNext(change);
    }

    /** What a (re)connecting subscriber receives before live changes. */
    public sealed interface Start permits Start.Fresh, Start.Replay, Start.Reset {
        record Fresh() implements Start {}
        record Replay(List<AdminChange> missed) implements Start {}
        record Reset() implements Start {}
    }

    synchronized Start startFor(String tenant, String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) return new Start.Fresh();
        int dash = lastEventId.lastIndexOf('-');
        if (dash <= 0 || !lastEventId.substring(0, dash).equals(bootId)) return new Start.Reset();
        long after;
        try {
            after = Long.parseLong(lastEventId.substring(dash + 1));
        } catch (NumberFormatException e) {
            return new Start.Reset();
        }
        if (after > seq) return new Start.Reset();
        AdminChange oldest = recent.peekFirst();
        if (after < seq && (oldest == null || oldest.seq() > after + 1)) return new Start.Reset();
        List<AdminChange> missed = new ArrayList<>();
        for (AdminChange c : recent) {
            if (c.seq() > after && c.visibleTo(tenant)) missed.add(c.forSubscriber());
        }
        return new Start.Replay(missed);
    }

    /** One frame of a subscriber's stream: a change, or "your cursor is lost — refetch everything". */
    public sealed interface Frame permits Frame.Change, Frame.Reset {
        record Change(AdminChange change) implements Frame {}
        record Reset() implements Frame {}
    }

    /**
     * Replay (or reset) followed by live changes, gap-free: the live
     * subscription is taken and the replay emitted under the same lock
     * {@link #publish} holds, so no change can fall between the two.
     */
    public Flux<Frame> stream(String tenant, String lastEventId) {
        return Flux.create(emitter -> {
            synchronized (this) {
                Start start = startFor(tenant, lastEventId);
                var live = sink.asFlux()
                        .filter(c -> c.visibleTo(tenant))
                        .map(AdminChange::forSubscriber)
                        .subscribe(c -> emitter.next(new Frame.Change(c)), emitter::error, emitter::complete);
                emitter.onDispose(live);
                switch (start) {
                    case Start.Reset r -> emitter.next(new Frame.Reset());
                    case Start.Replay r -> r.missed().forEach(c -> emitter.next(new Frame.Change(c)));
                    case Start.Fresh f -> { }
                }
            }
        });
    }

    public String bootId() {
        return bootId;
    }
}
