package com.yayatechandinnovations.yayaagentic.channel;

import java.time.Duration;
import java.time.Instant;

/**
 * Outcome of an outbound dispatch. The engine treats
 * {@link OutsideServiceWindow} and {@link TemplateRequired} as engine-level
 * back-pressure: the buffered reply is parked in {@code outbound_pending} and
 * the next inbound from the same end user releases it (back inside the
 * window). {@link OptedOut} is silent — Meta policy forbids contacting an
 * opted-out user, period. {@link ProviderFailure} feeds the retry policy
 * (§10.3) when {@code retriable=true}; otherwise audits as terminal.
 *
 * <p>See {@code whatsapp-channel-design.md} §10 (appendix lists the five
 * variants and §10.2 spells out the engine-side back-pressure semantics).
 */
public sealed interface OutboundResult
        permits OutboundResult.Sent,
                OutboundResult.OutsideServiceWindow,
                OutboundResult.TemplateRequired,
                OutboundResult.OptedOut,
                OutboundResult.ProviderFailure {

    record Sent(String messageSid, Instant sentAt) implements OutboundResult {}

    record OutsideServiceWindow(Duration sinceInbound) implements OutboundResult {}

    record TemplateRequired(String suggestedTemplate) implements OutboundResult {}

    record OptedOut(Instant since) implements OutboundResult {}

    record ProviderFailure(String code, String message, boolean retriable) implements OutboundResult {}
}
