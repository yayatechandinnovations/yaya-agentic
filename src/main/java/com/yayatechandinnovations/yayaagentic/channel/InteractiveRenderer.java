package com.yayatechandinnovations.yayaagentic.channel;

import com.yayatechandinnovations.yayaagentic.engine.TurnEvent;

/**
 * Channel-agnostic UiHint → channel-native interactive payload renderer.
 * One implementation per BSP/channel pair (Twilio WhatsApp, future
 * MessageBird WhatsApp, future Telegram inline-keyboards) so a non-Twilio
 * BSP can drop in without changing the engine.
 *
 * <p>See {@code whatsapp-channel-design.md} §10.2 + §10.4.
 */
public interface InteractiveRenderer {

    /**
     * @param bodyText the concatenated {@code Token} stream up to {@code End}
     * @param hint     the last {@link TurnEvent.UiHint} captured during
     *                 buffering, or {@code null} if the turn had none
     * @param ctx      per-call context — citation footnotes, channel kind
     */
    InteractivePayload render(String bodyText, TurnEvent.UiHint hint, RenderContext ctx);

    /** Per-render context. {@code citationFootnote} truncated to WhatsApp's
     *  60-char footer cap before reaching the renderer. */
    record RenderContext(ChannelKind channel, String citationFootnote) {}
}
