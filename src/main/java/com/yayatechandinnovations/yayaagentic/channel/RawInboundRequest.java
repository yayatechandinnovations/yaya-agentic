package com.yayatechandinnovations.yayaagentic.channel;

import java.util.Map;

/**
 * The webhook payload as it arrived from the BSP, untyped, pre-validation.
 * The {@link MessagingChannelProvider#validateInbound} call uses this PLUS the
 * sender's {@link SenderTrustMaterial} to verify the signature; only after a
 * positive validation does the adapter (
 * {@link MessagingChannelAdapter#parseInbound}) crack the body open.
 *
 * <p>Stored verbatim in {@code inbound_messages.raw_payload_json} (§13) for
 * replay / debug.
 *
 * @param senderIdHint  the {@code {senderId}} path segment from the webhook
 *                      URL (§14.1); a routing hint, NOT the canonical sender
 *                      identity — that's derived from {@code To} after the
 *                      body parses
 * @param method        HTTP method
 * @param path          the full request path (used by Twilio's signature math)
 * @param headers       lowercased header names → values (only one value per
 *                      header; multi-valued headers are joined comma-separated
 *                      per RFC 7230)
 * @param formParams    decoded application/x-www-form-urlencoded params (Twilio
 *                      sends form, not JSON)
 * @param rawBody       the body as bytes — kept so signature math can be byte-
 *                      exact even if header decoding drifts; nullable for
 *                      providers that only ship form params
 */
public record RawInboundRequest(
        String              senderIdHint,
        String              method,
        String              path,
        Map<String, String> headers,
        Map<String, String> formParams,
        byte[]              rawBody
) {

    public RawInboundRequest {
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("method required");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path required");
        }
        headers    = headers    == null ? Map.of() : Map.copyOf(headers);
        formParams = formParams == null ? Map.of() : Map.copyOf(formParams);
    }
}
