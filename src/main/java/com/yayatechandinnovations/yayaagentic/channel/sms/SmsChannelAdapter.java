package com.yayatechandinnovations.yayaagentic.channel.sms;

import com.yayatechandinnovations.yayaagentic.channel.ChannelDeliveryContext;
import com.yayatechandinnovations.yayaagentic.channel.ChannelKind;
import com.yayatechandinnovations.yayaagentic.channel.CompletionPolicy;
import com.yayatechandinnovations.yayaagentic.channel.InboundMessage;
import com.yayatechandinnovations.yayaagentic.channel.MessagingChannelAdapter;
import com.yayatechandinnovations.yayaagentic.channel.MessagingChannelProvider;
import com.yayatechandinnovations.yayaagentic.channel.OutboundEnvelope;
import com.yayatechandinnovations.yayaagentic.channel.RawInboundRequest;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Channel adapter for SMS. Declares the channel kind and the
 * {@link CompletionPolicy.Complete} behavior (buffer the engine's token stream,
 * dispatch one finalized message) — SMS has no streaming surface. Segment-aware
 * via {@link SmsSegmenter}: the dispatcher reads {@link #segments} to record
 * billing units, it never truncates ({@code sms-channel-design.md} §5.1).
 *
 * <p>{@link #parseInbound} and {@link #deliver} throw for now: SMS inbound +
 * delivery ride the same worker/dispatcher path as WhatsApp, which is still
 * concretely typed to {@code WhatsAppSender}. Generalizing it so SMS can route
 * through the engine is S3; until then this adapter pins the channel's policy
 * and segment logic without claiming a delivery path it doesn't have yet —
 * mirroring how {@code WebSseChannelAdapter} throws for its non-applicable
 * methods.
 */
@Component
public final class SmsChannelAdapter implements MessagingChannelAdapter {

    private static final ChannelKind      KIND   = new ChannelKind.Sms();
    private static final CompletionPolicy POLICY = new CompletionPolicy.Complete(Duration.ofSeconds(20));

    @Override
    public ChannelKind kind() {
        return KIND;
    }

    @Override
    public CompletionPolicy completionPolicy() {
        return POLICY;
    }

    /** Billed segment count for an outbound body — used by the dispatcher (S3)
     *  to stamp {@code outbound_messages.segment_count} and by evals to warn on
     *  chronically long replies. */
    public int segments(String body) {
        return SmsSegmenter.segmentCount(body);
    }

    @Override
    public InboundMessage parseInbound(RawInboundRequest req, MessagingChannelProvider p) {
        throw new UnsupportedOperationException(
                "SMS inbound parsing lands with the dispatcher generalization (S3)");
    }

    @Override
    public void deliver(OutboundEnvelope env, ChannelDeliveryContext ctx) {
        throw new UnsupportedOperationException(
                "SMS delivery is dispatcher-mediated; the dispatcher generalization is S3");
    }
}
