package com.yayatechandinnovations.yayaagentic.channel.system;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.outreach.ActionItem;

import java.util.List;
import java.util.Map;

/**
 * The ingest envelope an external system posts to inject a message into a user's
 * conversation (§5). The tenant and sender are fixed by the sender row + the
 * authenticated request — never trusted from the wire body.
 *
 * <p>SM-0 carries text only; media rides the same shape from SM-1 (§10).
 *
 * @param recipient WHO — resolved to the user's established chat (§6)
 * @param body      human-readable text (SM-0 requires it non-blank)
 * @param correlation WHO to answer and about what — captured for the reply loop (§8)
 * @param dedupKey  idempotency handle, unique per (tenant, sender)
 * @param metadata  opaque passthrough recorded on the Turn
 */
public record SystemInboundMessage(
        Ids.TenantId tenant,
        Ids.SystemSenderId senderId,
        OutreachRecipient recipient,
        String body,
        List<MediaRef> media,
        Correlation correlation,
        String dedupKey,
        Map<String, Object> metadata,
        List<ActionItem> actions,
        DeliveryRequest delivery
) {
    public SystemInboundMessage {
        if (tenant == null) throw new IllegalArgumentException("tenant required");
        if (senderId == null) throw new IllegalArgumentException("senderId required");
        if (recipient == null) throw new IllegalArgumentException("recipient required");
        media = media == null ? List.of() : List.copyOf(media);
        // Body may be empty when media-only; require at least one of body/media.
        if ((body == null || body.isBlank()) && media.isEmpty())
            throw new IllegalArgumentException("body or media required");
        if (dedupKey == null || dedupKey.isBlank())
            throw new IllegalArgumentException("dedupKey required");
        correlation = correlation == null ? new Correlation("", "", "") : correlation;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        actions = actions == null ? List.of() : List.copyOf(actions);
        delivery = delivery == null ? DeliveryRequest.chat() : delivery;
        if (delivery.wantsWhatsApp() && delivery.phoneE164() == null)
            throw new IllegalArgumentException("delivery.phoneE164 required for WHATSAPP");
        for (ActionItem a : actions) {
            if (a.label() == null || a.label().isBlank())
                throw new IllegalArgumentException("action label required");
            if (!a.bound() && (a.send() == null || a.send().isBlank()))
                throw new IllegalArgumentException("action needs send text or a tool");
        }
    }

    /** Back-compat: chat-only delivery. */
    public SystemInboundMessage(Ids.TenantId tenant, Ids.SystemSenderId senderId,
                                OutreachRecipient recipient, String body, List<MediaRef> media,
                                Correlation correlation, String dedupKey, Map<String, Object> metadata,
                                List<ActionItem> actions) {
        this(tenant, senderId, recipient, body, media, correlation, dedupKey, metadata, actions, null);
    }

    /** Back-compat: an envelope with no inline action buttons (§D3). */
    public SystemInboundMessage(Ids.TenantId tenant, Ids.SystemSenderId senderId,
                                OutreachRecipient recipient, String body, List<MediaRef> media,
                                Correlation correlation, String dedupKey, Map<String, Object> metadata) {
        this(tenant, senderId, recipient, body, media, correlation, dedupKey, metadata, List.of(), null);
    }
}
