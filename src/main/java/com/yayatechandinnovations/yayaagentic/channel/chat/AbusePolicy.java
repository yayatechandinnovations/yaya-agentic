package com.yayatechandinnovations.yayaagentic.channel.chat;

/**
 * Per-entry-point abuse controls for the public front door
 * ({@code chat-channel-design.md} §7.1). The chat surface is internet-facing
 * and adversarial — unlike the platform-internal WhatsApp/SMS webhooks — so
 * every anonymous session is rate- and budget-capped. {@code anonymousDailyMessageCap}
 * is the LLM-cost circuit breaker: anonymous chat is free to the end user and
 * therefore the natural abuse target.
 *
 * <p>Persisted as JSONB; defaults applied here so an empty {@code {}} column
 * deserializes to sane limits.
 */
public record AbusePolicy(
        int startsPerDevicePerHour,
        int startsPerIpPerHour,
        int messagesPerSessionPerHour,
        int anonymousDailyMessageCap,
        Challenge challenge
) {
    public enum Challenge { NONE, TURNSTILE, PROOF_OF_WORK }

    public AbusePolicy {
        if (startsPerDevicePerHour <= 0) startsPerDevicePerHour = 10;
        if (startsPerIpPerHour <= 0) startsPerIpPerHour = 60;
        if (messagesPerSessionPerHour <= 0) messagesPerSessionPerHour = 120;
        if (anonymousDailyMessageCap <= 0) anonymousDailyMessageCap = 200;
        if (challenge == null) challenge = Challenge.NONE;
    }

    public static AbusePolicy defaults() {
        return new AbusePolicy(10, 60, 120, 200, Challenge.NONE);
    }
}
