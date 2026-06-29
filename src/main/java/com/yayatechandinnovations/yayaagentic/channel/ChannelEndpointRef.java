package com.yayatechandinnovations.yayaagentic.channel;

import com.yayatechandinnovations.yayaagentic.channel.sms.SmsSenderId;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;

/**
 * Pointer to a concrete channel endpoint (a registered WhatsApp sender, an SMS
 * sender, a future Telegram bot) — what a {@link ChannelBinding} routes to.
 * Sealed so adding an endpoint kind forces every {@code switch} over the type
 * to be updated, including the {@code endpoint_kind} CHECK constraint (extended
 * for SMS in {@code V45__sms_channel_wiring.sql}).
 *
 * <p>See {@code whatsapp-channel-design.md} §8 (binding shape).
 */
public sealed interface ChannelEndpointRef
        permits ChannelEndpointRef.WhatsAppSenderRef, ChannelEndpointRef.SmsSenderRef {

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

    record SmsSenderRef(SmsSenderId id) implements ChannelEndpointRef {
        public SmsSenderRef {
            if (id == null) throw new IllegalArgumentException("id required");
        }
        @Override public String kindName()   { return "SMS_SENDER"; }
        @Override public String externalId() { return id.value(); }
    }
}
