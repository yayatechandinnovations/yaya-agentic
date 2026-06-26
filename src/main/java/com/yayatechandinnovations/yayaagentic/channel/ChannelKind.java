package com.yayatechandinnovations.yayaagentic.channel;

/**
 * The transport class of a conversation. Promotes the legacy
 * {@code StartConversationRequest.channel} string to a typed sealed value so
 * the engine, the adapter, and the profile resolver chain can pattern-match
 * over it without string sniffing.
 *
 * <p>v1 ships {@link WebSse} (the existing playground / SSE controller path)
 * and {@link WhatsApp}. {@link Sms} and {@link Telegram} are listed only to
 * pin the SPI shape — no implementations.
 *
 * <p>See {@code whatsapp-channel-design.md} §5.1.
 */
public sealed interface ChannelKind
        permits ChannelKind.WebSse,
                ChannelKind.WhatsApp,
                ChannelKind.Sms,
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

    record Telegram() implements ChannelKind {
        @Override public String wireName() { return "telegram"; }
    }
}
