package com.yayatechandinnovations.yayaagentic.channel;

import java.util.Map;

/**
 * A media message to a BSP channel (§10, §13). The BSP fetches {@code mediaUrl}
 * — our short-lived signed {@code /v1/media/{token}} URL, the outbound mirror of
 * the inbound egress allow-list — so bytes never ride the request.
 *
 * @param recipientE164 channel-native recipient
 * @param mediaUrl      signed, short-lived URL the BSP retrieves the media from
 * @param caption       optional text delivered alongside the media
 * @param contentType   MIME of the media (drives WhatsApp document vs image)
 * @param filename      document filename (shown by the client for documents)
 * @param providerHints per-BSP routing (e.g. {@code fromPhoneE164})
 */
public record OutboundMedia(
        String recipientE164,
        String mediaUrl,
        String caption,
        String contentType,
        String filename,
        Map<String, Object> providerHints
) {
    public OutboundMedia {
        if (recipientE164 == null || recipientE164.isBlank()) {
            throw new IllegalArgumentException("recipientE164 required");
        }
        if (mediaUrl == null || mediaUrl.isBlank()) {
            throw new IllegalArgumentException("mediaUrl required");
        }
        providerHints = providerHints == null ? Map.of() : Map.copyOf(providerHints);
    }
}
