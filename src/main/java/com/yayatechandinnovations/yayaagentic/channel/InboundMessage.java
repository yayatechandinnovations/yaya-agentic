package com.yayatechandinnovations.yayaagentic.channel;

import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The channel adapter's pre-engine representation of an inbound message —
 * what {@link MessagingChannelAdapter#parseInbound} produces after the BSP
 * signature check passes. This is NOT the engine's {@code UserMessage};
 * the dispatch worker (§9 step 6) converts to it after the principal mint,
 * session resolve, and (for audio) media transcription steps.
 *
 * @param tenant         tenant the sender belongs to (resolved from the
 *                       sender row, not trusted from the wire)
 * @param channel        which channel kind produced this
 * @param senderRef      the BSP-side sender SID
 * @param messageSid     BSP message identifier (Twilio {@code SM...}); used
 *                       for {@code inbound_messages} dedup (§9 step 3)
 * @param endUserAddress channel-native end-user identifier
 *                       (E.164 phone for WhatsApp / SMS;
 *                       Telegram chat id for Telegram, etc.)
 * @param locale         BCP-47 locale hint when the BSP provides one (Twilio
 *                       does not for WhatsApp; null in that case)
 * @param body           raw text body — {@code null} when the message was
 *                       media-only (the worker fills body from transcription
 *                       per §9.5)
 * @param media          attached media descriptors (signed URLs, content
 *                       types); the worker re-fetches via the egress allowlist
 * @param interactiveId  on a button/list reply, the structured option id
 *                       (§10.3); null otherwise
 * @param raw            the unmodified BSP-shape map (also stored in
 *                       {@code inbound_messages.raw_payload_json} for replay)
 * @param receivedAt     when the webhook hit our edge
 */
public record InboundMessage(
        Ids.TenantId        tenant,
        ChannelKind         channel,
        SenderRef           senderRef,
        String              messageSid,
        String              endUserAddress,
        String              locale,
        String              body,
        List<MediaRef>      media,
        String              interactiveId,
        Map<String, Object> raw,
        Instant             receivedAt
) {

    public InboundMessage {
        if (tenant == null) {
            throw new IllegalArgumentException("tenant required");
        }
        if (channel == null) {
            throw new IllegalArgumentException("channel required");
        }
        if (senderRef == null) {
            throw new IllegalArgumentException("senderRef required");
        }
        if (messageSid == null || messageSid.isBlank()) {
            throw new IllegalArgumentException("messageSid must not be blank");
        }
        if (endUserAddress == null || endUserAddress.isBlank()) {
            throw new IllegalArgumentException("endUserAddress must not be blank");
        }
        if (receivedAt == null) {
            throw new IllegalArgumentException("receivedAt required");
        }
        media = media == null ? List.of() : List.copyOf(media);
        raw   = raw   == null ? Map.of()  : Map.copyOf(raw);
    }

    /**
     * Reference to a piece of inbound media (image, audio, document). Concrete
     * fetch happens in the dispatch worker — the controller path stays cheap
     * so we ack Twilio within 15s (§9.1).
     *
     * @param mediaUrl    signed BSP-CDN URL; valid for ~24h (Twilio rotates)
     * @param contentType MIME — drives transcriber selection in §9.5
     * @param sizeBytes   -1 when the BSP doesn't advertise it
     */
    public record MediaRef(String mediaUrl, String contentType, long sizeBytes) {

        public MediaRef {
            if (mediaUrl == null || mediaUrl.isBlank()) {
                throw new IllegalArgumentException("mediaUrl must not be blank");
            }
            if (contentType == null || contentType.isBlank()) {
                throw new IllegalArgumentException("contentType must not be blank");
            }
        }
    }
}
