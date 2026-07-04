package com.yayatechandinnovations.yayaagentic.channel.system;

import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Marketing-style guardrails on the wake nudge (§3, §16 #9). These throttle the
 * best-effort push wake — never the durable delivery: an integration message is
 * always recorded + inboxed (idempotent, store-and-forward). During quiet hours
 * or inside the per-recipient cooldown, we still deliver, we just don't buzz the
 * device.
 *
 * @param quietStartHourUtc       start of the do-not-disturb window (0–23, UTC);
 *                                equal to end = no quiet hours
 * @param quietEndHourUtc         end of the do-not-disturb window (0–23, UTC)
 * @param minMinutesBetweenNudges per-recipient cooldown between wake nudges
 */
public record RateAndQuiet(
        int quietStartHourUtc,
        int quietEndHourUtc,
        int minMinutesBetweenNudges
) {
    public static RateAndQuiet none() {
        return new RateAndQuiet(0, 0, 0);
    }

    public boolean hasQuietHours() {
        return quietStartHourUtc != quietEndHourUtc;
    }

    /** True if {@code now} falls inside the quiet window (handles midnight wrap). */
    public boolean isQuietAt(Instant now) {
        if (!hasQuietHours()) return false;
        int h = now.atZone(ZoneOffset.UTC).getHour();
        if (quietStartHourUtc < quietEndHourUtc) {
            return h >= quietStartHourUtc && h < quietEndHourUtc;
        }
        // Wraps midnight, e.g. 22 → 7.
        return h >= quietStartHourUtc || h < quietEndHourUtc;
    }
}
