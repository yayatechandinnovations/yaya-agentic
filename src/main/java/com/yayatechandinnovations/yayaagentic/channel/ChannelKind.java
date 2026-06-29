package com.yayatechandinnovations.yayaagentic.channel;

/**
 * The transport class of a conversation. Promotes the legacy
 * {@code StartConversationRequest.channel} string to a typed sealed value so
 * the engine, the adapter, and the profile resolver chain can pattern-match
 * over it without string sniffing.
 *
 * <p>v1 ships {@link WebSse} (the existing playground / SSE controller path),
 * {@link WhatsApp}, {@link Sms}, and {@link Chat} (the public, branded end-user
 * chat app — {@code chat-channel-design.md} §5.1). {@link Telegram} is listed
 * only to pin the SPI shape — no implementation.
 *
 * <p>{@link Chat} shares the SSE wire with {@link WebSse} but is a distinct kind
 * so the operator playground and the public end-user front door stay on separate
 * trust boundaries and metrics.
 *
 * <p>See {@code whatsapp-channel-design.md} §5.1.
 */
public sealed interface ChannelKind
        permits ChannelKind.WebSse,
                ChannelKind.WhatsApp,
                ChannelKind.Sms,
                ChannelKind.Chat,
                ChannelKind.Telegram {

    /** Stable wire name used in audit, metrics labels, and the legacy
     *  {@code channel} string on {@code StartConversationRequest}. */
    String wireName();

    record WebSse() implements ChannelKind {
        @Override public String wireName() { return "web-sse"; }
    }

    record WhatsApp() implements ChannelKind {
        @Override public String wireName() { return "whatsapp"; }
    }

    record Sms() implements ChannelKind {
        @Override public String wireName() { return "sms"; }
    }

    record Chat() implements ChannelKind {
        @Override public String wireName() { return "chat"; }
    }

    record Telegram() implements ChannelKind {
        @Override public String wireName() { return "telegram"; }
    }
}
