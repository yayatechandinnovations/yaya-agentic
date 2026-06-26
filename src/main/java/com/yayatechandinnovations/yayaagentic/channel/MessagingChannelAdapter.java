package com.yayatechandinnovations.yayaagentic.channel;

/**
 * Per-{@link ChannelKind} bridge between the BSP wire and the engine's typed
 * {@code UserMessage} / {@code TurnEvent} flux. One implementation per channel
 * kind — the WhatsApp adapter is identical whether the BSP is Twilio or
 * MessageBird; only the {@link MessagingChannelProvider} changes underneath.
 *
 * <p>See {@code whatsapp-channel-design.md} §5.1, §5.2 (why split provider from
 * adapter).
 */
public interface MessagingChannelAdapter {

    /** Which kind of channel this adapter speaks. */
    ChannelKind kind();

    /** Stream-vs-buffer behavior (§5.1 #1, §10.1). */
    CompletionPolicy completionPolicy();

    /**
     * Crack open a validated webhook payload into the channel-side
     * {@link InboundMessage} shape. The controller has already run
     * {@link MessagingChannelProvider#validateInbound} — adapters MUST NOT
     * second-guess that.
     *
     * <p>For channels that are NOT BSP-pushed (e.g. {@code WebSse}, whose
     * inbound arrives as a typed REST body through the existing
     * {@code SessionController}) implementations throw
     * {@link UnsupportedOperationException}.
     */
    InboundMessage parseInbound(RawInboundRequest req, MessagingChannelProvider p);

    /**
     * Dispatch one finalized outbound. Called by the outbound dispatcher for
     * {@link CompletionPolicy.Complete} adapters; for {@link CompletionPolicy.Stream}
     * adapters delivery is controller-mediated (the framework writes the
     * SSE response) and implementations throw
     * {@link UnsupportedOperationException}.
     */
    void deliver(OutboundEnvelope env, ChannelDeliveryContext ctx);
}
