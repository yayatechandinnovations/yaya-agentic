package com.yayatechandinnovations.yayaagentic.channel.chat;

import com.yayatechandinnovations.yayaagentic.channel.ChannelDeliveryContext;
import com.yayatechandinnovations.yayaagentic.channel.ChannelKind;
import com.yayatechandinnovations.yayaagentic.channel.CompletionPolicy;
import com.yayatechandinnovations.yayaagentic.channel.InboundMessage;
import com.yayatechandinnovations.yayaagentic.channel.MessagingChannelAdapter;
import com.yayatechandinnovations.yayaagentic.channel.MessagingChannelProvider;
import com.yayatechandinnovations.yayaagentic.channel.RawInboundRequest;
import com.yayatechandinnovations.yayaagentic.channel.websse.WebSseChannelAdapter;
import com.yayatechandinnovations.yayaagentic.engine.TurnEvent;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;

/**
 * Channel adapter for the public end-user chat surface ({@code chat-channel-design.md} §5, §9.1).
 * Like {@link WebSseChannelAdapter}, chat is a streaming, controller-mediated
 * channel: {@link #parseInbound} and {@link #deliver} both throw because inbound
 * arrives as a typed REST body and delivery is the SSE response the controller
 * writes from the returned {@code Flux<TurnEvent>}.
 *
 * <p>The {@code TurnEvent → ServerSentEvent} mapping is shared with web-SSE — one
 * wire format, two front doors — so this adapter delegates to
 * {@link WebSseChannelAdapter#toSse}.
 */
@Component
public final class ChatChannelAdapter implements MessagingChannelAdapter {

    private static final ChannelKind      KIND   = new ChannelKind.Chat();
    private static final CompletionPolicy POLICY = new CompletionPolicy.Stream();

    private final WebSseChannelAdapter webSse;

    public ChatChannelAdapter(WebSseChannelAdapter webSse) {
        this.webSse = webSse;
    }

    @Override
    public ChannelKind kind() {
        return KIND;
    }

    @Override
    public CompletionPolicy completionPolicy() {
        return POLICY;
    }

    @Override
    public InboundMessage parseInbound(RawInboundRequest req, MessagingChannelProvider p) {
        throw new UnsupportedOperationException(
                "chat inbound arrives as a typed REST body via the public ChatController, not a webhook");
    }

    @Override
    public void deliver(com.yayatechandinnovations.yayaagentic.channel.OutboundEnvelope env,
                        ChannelDeliveryContext ctx) {
        throw new UnsupportedOperationException(
                "chat delivery is controller-mediated; the SSE pipe is the wire");
    }

    /** Map a single {@code TurnEvent} to the SSE event the wire emits. */
    public ServerSentEvent<Object> toSse(TurnEvent event) {
        return webSse.toSse(event);
    }
}
