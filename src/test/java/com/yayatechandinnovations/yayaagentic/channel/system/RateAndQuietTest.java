package com.yayatechandinnovations.yayaagentic.channel.system;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** §3 guardrails throttle the wake nudge (never the durable delivery). */
class RateAndQuietTest {

    private static Instant atUtcHour(int hour) {
        return ZonedDateTime.of(2026, 7, 3, hour, 30, 0, 0, ZoneOffset.UTC).toInstant();
    }

    @Test
    void noQuietHoursWhenStartEqualsEnd() {
        RateAndQuiet r = RateAndQuiet.none();
        assertThat(r.hasQuietHours()).isFalse();
        assertThat(r.isQuietAt(atUtcHour(3))).isFalse();
    }

    @Test
    void quietWindowWithinDay() {
        RateAndQuiet r = new RateAndQuiet(9, 17, 0); // 09:00–17:00
        assertThat(r.isQuietAt(atUtcHour(12))).isTrue();
        assertThat(r.isQuietAt(atUtcHour(8))).isFalse();
        assertThat(r.isQuietAt(atUtcHour(17))).isFalse();
    }

    @Test
    void quietWindowWrappingMidnight() {
        RateAndQuiet r = new RateAndQuiet(22, 7, 0); // 22:00–07:00
        assertThat(r.isQuietAt(atUtcHour(23))).isTrue();
        assertThat(r.isQuietAt(atUtcHour(3))).isTrue();
        assertThat(r.isQuietAt(atUtcHour(12))).isFalse();
    }
}
