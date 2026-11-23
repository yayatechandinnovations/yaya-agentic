package com.yayatechandinnovations.yayaagentic.adminevents;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdminChangeFeedTest {

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-22T10:00:00Z");
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    private final MutableClock clock = new MutableClock();
    private final AdminChangeFeed feed = new AdminChangeFeed(clock);

    private List<AdminChange> replayed(String tenant, String lastEventId) {
        var start = feed.startFor(tenant, lastEventId);
        assertThat(start).isInstanceOf(AdminChangeFeed.Start.Replay.class);
        return ((AdminChangeFeed.Start.Replay) start).missed();
    }

    @Test
    void replays_only_changes_after_the_cursor_and_only_for_the_subscribers_tenant() {
        feed.publish("acme", "TOOL", "a", 1, AdminChange.Op.CREATED);
        String cursor = feed.bootId() + "-1";
        feed.publish("other", "TOOL", "b", 1, AdminChange.Op.CREATED);
        feed.publish("acme", "PROFILE", "p", 2, AdminChange.Op.CREATED);

        assertThat(replayed("acme", cursor)).extracting(AdminChange::kind, AdminChange::id)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("PROFILE", "p"));
    }

    @Test
    void platform_changes_reach_every_tenant_without_naming_the_row() {
        feed.publish(null, "TENANT", null, null, AdminChange.Op.UPDATED);
        feed.publish("acme", "TOOL", "a", 1, AdminChange.Op.CREATED);

        var missed = replayed("other", feed.bootId() + "-0");
        assertThat(missed).hasSize(1);
        assertThat(missed.get(0).kind()).isEqualTo("TENANT");
        assertThat(missed.get(0).id()).isNull();
    }

    @Test
    void a_cursor_from_another_boot_or_older_than_the_buffer_asks_for_a_reset() {
        assertThat(feed.startFor("acme", "zzz-4")).isInstanceOf(AdminChangeFeed.Start.Reset.class);
        assertThat(feed.startFor("acme", feed.bootId() + "-99")).isInstanceOf(AdminChangeFeed.Start.Reset.class);

        for (int i = 0; i < AdminChangeFeed.REPLAY_CAPACITY + 5; i++) {
            feed.publish("acme", "TOOL", "t" + i, 1, AdminChange.Op.CREATED);
        }
        assertThat(feed.startFor("acme", feed.bootId() + "-1")).isInstanceOf(AdminChangeFeed.Start.Reset.class);
        assertThat(feed.startFor("acme", null)).isInstanceOf(AdminChangeFeed.Start.Fresh.class);
    }

    @Test
    void rapid_updates_of_one_row_collapse_but_creates_and_deletes_always_pass() {
        feed.publish("acme", "CHAT", "42", null, AdminChange.Op.UPDATED);
        feed.publish("acme", "CHAT", "42", null, AdminChange.Op.UPDATED);
        feed.publish("acme", "CHAT", "42", null, AdminChange.Op.DELETED);
        clock.advance(Duration.ofSeconds(2));
        feed.publish("acme", "CHAT", "42", null, AdminChange.Op.UPDATED);

        assertThat(replayed("acme", feed.bootId() + "-0")).extracting(AdminChange::op).containsExactly(
                AdminChange.Op.UPDATED, AdminChange.Op.DELETED, AdminChange.Op.UPDATED);
    }

    @Test
    void stream_replays_then_goes_live_without_a_gap() throws Exception {
        feed.publish("acme", "TOOL", "a", 1, AdminChange.Op.CREATED);
        String cursor = feed.bootId() + "-0";

        var frames = feed.stream("acme", cursor).take(2).collectList().toFuture();
        feed.publish("acme", "TOOL", "b", 1, AdminChange.Op.CREATED);

        assertThat(frames.get(5, java.util.concurrent.TimeUnit.SECONDS))
                .extracting(f -> ((AdminChangeFeed.Frame.Change) f).change().id())
                .containsExactly("a", "b");
    }

    @Test
    void stream_with_a_lost_cursor_starts_with_a_reset_frame() {
        assertThat(feed.stream("acme", "nope-3").blockFirst(Duration.ofSeconds(5)))
                .isInstanceOf(AdminChangeFeed.Frame.Reset.class);
    }
}
