package com.yayatechandinnovations.yayaagentic.channel;

import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;

/**
 * Pointer to a concrete channel endpoint (a registered WhatsApp sender, a
 * future SMS long-code, a Telegram bot) — what a {@link ChannelBinding}
 * routes to. Sealed so adding an endpoint kind forces every {@code switch}
 * over the type to be updated, including the {@code endpoint_kind} CHECK
 * constraint in {@code V26__channel_bindings.sql}.
 *
 * <p>See {@code whatsapp-channel-design.md} §8 (binding shape).
 */
public sealed interface ChannelEndpointRef
        permits ChannelEndpointRef.WhatsAppSenderRef {

    /** Stable wire name used in {@code channel_bindings.endpoint_kind}. */
    String kindName();

    /** Channel-stable id of the underlying endpoint row. */
    String externalId();

    record WhatsAppSenderRef(SenderId id) implements ChannelEndpointRef {
        public WhatsAppSenderRef {
            if (id == null) throw new IllegalArgumentException("id required");
        }
        @Override public String kindName()   { return "WHATSAPP_SENDER"; }
        @Override public String externalId() { return id.value(); }
    }
}
