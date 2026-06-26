package com.yayatechandinnovations.yayaagentic.channel;

import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the engine has decided to send on a given turn, in a channel-agnostic
 * shape. For a {@link CompletionPolicy.Complete} adapter (WhatsApp) the
 * envelope is built AFTER buffering the {@code TurnEvent} flux (§10.1); for a
 * {@link CompletionPolicy.Stream} adapter (web SSE) the envelope is unused
 * because delivery happens through the controller's reactive pipe directly.
 *
 * <p>This record stays free of BSP types — the BSP-shaped
 * {@link OutboundFreeform} / {@link OutboundTemplate} are derived by the
 * channel-kind code (WhatsApp) just before the provider call.
 *
 * @param tenant         tenant whose recorder + audit owns this dispatch
 * @param session        the engine session this outbound closes
 * @param channel        which channel kind to deliver on
 * @param recipient      channel-native end-user identifier (E.164 phone, …)
 * @param body           buffered text body
 * @param interactive    last {@code TurnEvent.UiHint.payload} captured during
 *                       buffering (§10.1, §10.2). Earlier hints in the same
 *                       turn were recorded but dropped — the renderer
 *                       (W5) consumes only the last
 * @param citations      compact recorder-side citation refs surfaced during
 *                       the turn (kept for analytics; not rendered in v1
 *                       WhatsApp body)
 * @param continuation   true when this envelope is a follow-up flush from
 *                       hitting the {@code Complete.maxBuffer} guardrail —
 *                       the adapter prepends a soft "(more…)" marker
 */
public record OutboundEnvelope(
        Ids.TenantId                 tenant,
        Ids.SessionId                session,
        ChannelKind                  channel,
        String                       recipient,
        String                       body,
        Optional<Map<String, Object>> interactive,
        List<String>                 citations,
        boolean                      continuation
) {

    public OutboundEnvelope {
        if (tenant == null) {
            throw new IllegalArgumentException("tenant required");
        }
        if (session == null) {
            throw new IllegalArgumentException("session required");
        }
        if (channel == null) {
            throw new IllegalArgumentException("channel required");
        }
        if (recipient == null || recipient.isBlank()) {
            throw new IllegalArgumentException("recipient must not be blank");
        }
        if (body == null) {
            throw new IllegalArgumentException("body required (may be empty for interactive-only)");
        }
        interactive = interactive == null ? Optional.empty() : interactive;
        citations   = citations   == null ? List.of() : List.copyOf(citations);
    }
}
