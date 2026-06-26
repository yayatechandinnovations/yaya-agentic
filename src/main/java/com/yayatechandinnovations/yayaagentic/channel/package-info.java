/**
 * Channel — the transport an end-user conversation arrives over. Promotes the
 * legacy free-form {@code channel} string on {@code StartConversationRequest}
 * to a typed first-class concept with its own SPI surface so a BSP-backed
 * channel (WhatsApp via Twilio) can plug in without forking the engine.
 *
 * <p>Two SPIs split the concerns:
 * <ul>
 *   <li>{@link com.yayatechandinnovations.yayaagentic.channel.MessagingChannelProvider}
 *       — per-BSP. Twilio's API surface for sender registration, inbound
 *       signature validation, outbound send (free-form / template), and
 *       template sync.</li>
 *   <li>{@link com.yayatechandinnovations.yayaagentic.channel.MessagingChannelAdapter}
 *       — per-{@link com.yayatechandinnovations.yayaagentic.channel.ChannelKind}.
 *       Bridges between the BSP-shaped wire payload and the engine's typed
 *       {@code UserMessage} / {@code TurnEvent} flux, respecting the channel's
 *       {@link com.yayatechandinnovations.yayaagentic.channel.CompletionPolicy}
 *       (stream vs. buffer-then-send).</li>
 * </ul>
 *
 * See {@code docs/design/whatsapp-channel-design.md} §5 (Channel as first-class)
 * and §10 (Outbound dispatch). The companion master doc is
 * {@code docs/design/yaya-agentic-design.md} §5.6 (Profile resolver chain).
 */
package com.yayatechandinnovations.yayaagentic.channel;
