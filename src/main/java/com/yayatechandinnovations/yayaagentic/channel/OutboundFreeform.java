package com.yayatechandinnovations.yayaagentic.channel;

import java.util.Map;
import java.util.Optional;

/**
 * BSP-bound free-form (in-service-window) message. The adapter builds this
 * from the concatenated {@code Token} stream + the last {@code UiHint} per
 * §10.1 buffering rules, then hands it to
 * {@link MessagingChannelProvider#sendFreeform}.
 *
 * @param recipientE164    destination phone in E.164
 * @param body             plain text body
 * @param interactiveHint  channel-agnostic interactive payload (the
 *                         {@code TurnEvent.UiHint.payload}) — the BSP renders
 *                         it to its native interactive surface. Empty when the
 *                         outbound has no buttons / list. Concrete typing
 *                         arrives in W5 with the {@code InteractiveRenderer}
 *                         SPI; this seam keeps the W0 shape forward-compat.
 * @param providerHints    BSP-specific knobs (e.g. {@code statusCallbackUrl}
 *                         override, sticky-sender pin) — kept open so the
 *                         provider can read its own dialect without leaking
 *                         BSP types into the channel core.
 */
public record OutboundFreeform(
        String                       recipientE164,
        String                       body,
        Optional<Map<String, Object>> interactiveHint,
        Map<String, Object>          providerHints
) {

    public OutboundFreeform {
        if (recipientE164 == null || recipientE164.isBlank()) {
            throw new IllegalArgumentException("recipientE164 must not be blank");
        }
        if (body == null) {
            throw new IllegalArgumentException("body required (may be empty for interactive-only)");
        }
        interactiveHint = interactiveHint == null ? Optional.empty() : interactiveHint;
        providerHints   = providerHints   == null ? Map.of() : Map.copyOf(providerHints);
    }
}
