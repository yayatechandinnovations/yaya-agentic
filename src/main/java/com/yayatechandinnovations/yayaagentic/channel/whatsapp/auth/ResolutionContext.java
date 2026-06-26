package com.yayatechandinnovations.yayaagentic.channel.whatsapp.auth;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Inbound metadata handed to {@link PhoneToPrincipalResolver#resolve}. Carries
 * everything the resolver needs without forcing it to re-derive context from
 * raw headers.
 *
 * @param receivedAt          when the inbound message arrived at the worker
 * @param locale              BCP-47 locale stamped by the channel (e.g. "es-DO"),
 *                            empty when unset
 * @param twilioContactSid    Twilio's stable identifier for the inbound user,
 *                            empty when unset
 * @param channelHeaders      the raw {@code x-channel-*} headers; resolvers
 *                            rarely need this but it's a useful escape hatch
 *                            for tenant-specific metadata
 */
public record ResolutionContext(
        Instant                     receivedAt,
        Optional<String>            locale,
        Optional<String>            twilioContactSid,
        Map<String, String>         channelHeaders) {

    public ResolutionContext {
        channelHeaders = channelHeaders == null ? Map.of() : Map.copyOf(channelHeaders);
        locale = locale == null ? Optional.empty() : locale;
        twilioContactSid = twilioContactSid == null ? Optional.empty() : twilioContactSid;
    }
}
