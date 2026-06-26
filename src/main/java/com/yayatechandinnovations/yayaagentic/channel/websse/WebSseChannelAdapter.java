package com.yayatechandinnovations.yayaagentic.channel.websse;

import com.yayatechandinnovations.yayaagentic.channel.ChannelDeliveryContext;
import com.yayatechandinnovations.yayaagentic.channel.ChannelKind;
import com.yayatechandinnovations.yayaagentic.channel.CompletionPolicy;
import com.yayatechandinnovations.yayaagentic.channel.InboundMessage;
import com.yayatechandinnovations.yayaagentic.channel.MessagingChannelAdapter;
import com.yayatechandinnovations.yayaagentic.channel.MessagingChannelProvider;
import com.yayatechandinnovations.yayaagentic.channel.OutboundEnvelope;
import com.yayatechandinnovations.yayaagentic.channel.RawInboundRequest;
import com.yayatechandinnovations.yayaagentic.engine.TurnEvent;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;

/**
 * Channel adapter for the existing web SSE path. The runtime behavior is
 * unchanged — {@code SessionController} still owns request handling and the
 * reactive pipe — this bean only centralizes the
 * {@code TurnEvent → ServerSentEvent} mapping so the WhatsApp adapter (W4+)
 * can sit beside it sharing the {@link MessagingChannelAdapter} shape.
 *
 * <p>{@link #parseInbound} and {@link #deliver} both throw: web SSE has no
 * BSP-pushed webhook surface and its delivery is controller-mediated (Spring
 * writes the SSE response from the returned {@code Flux}). The non-throwing
 * stream entry point is {@link #toSse(TurnEvent)}.
 */
@Component
public final class WebSseChannelAdapter implements MessagingChannelAdapter {

    private static final ChannelKind      KIND   = new ChannelKind.WebSse();
    private static final CompletionPolicy POLICY = new CompletionPolicy.Stream();

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
                "web-sse inbound arrives as a typed REST body via SessionController, not a webhook");
    }

    @Override
    public void deliver(OutboundEnvelope env, ChannelDeliveryContext ctx) {
        throw new UnsupportedOperationException(
                "web-sse delivery is controller-mediated; the SSE pipe is the wire");
    }

    /**
     * Map a single {@code TurnEvent} to the SSE event the wire emits. The
     * {@code event:} name matches the contract documented in
     * {@code yaya-agentic-design.md} §9 (Streaming events). Previously lived
     * as a private static in {@code SessionController}.
     */
    public ServerSentEvent<Object> toSse(TurnEvent event) {
        String name = switch (event) {
            case TurnEvent.Token      t -> "token";
            case TurnEvent.ToolCall   t -> "tool_call";
            case TurnEvent.ToolResult t -> "tool_result";
            case TurnEvent.Citation   c -> "citation";
            case TurnEvent.UiHint     u -> "ui_hint";
            case TurnEvent.End        e -> "end";
        };
        return ServerSentEvent.builder((Object) event).event(name).build();
    }
}
