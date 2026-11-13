package com.yayatechandinnovations.yayaagentic.api.dto;

import com.yayatechandinnovations.yayaagentic.channel.system.Correlation;
import com.yayatechandinnovations.yayaagentic.channel.system.MediaRef;
import com.yayatechandinnovations.yayaagentic.channel.system.OutreachRecipient;
import com.yayatechandinnovations.yayaagentic.outreach.OutreachService.OutreachReceipt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Wire DTOs for the ingest edge ({@code system-messaging-design.md} §5). The
 * tenant + sender are fixed by the URL and the authenticated request — the body
 * only carries the addressed content.
 */
public final class SystemInboundDtos {

    private SystemInboundDtos() {}

    /** {@code POST /v1/integrations/{tenant}/senders/{senderId}/messages} body. */
    public record IngestRequest(
            RecipientRef recipient,
            String body,
            List<MediaItem> media,
            CorrelationRef correlation,
            String dedupKey,
            Map<String, Object> metadata,
            /** Optional inline action buttons the message offers the operator (§D3). */
            List<ActionItem> actions,
            /** Optional preferred channel (whatsapp-ops-integration-design.md §14.1). */
            DeliveryRef delivery,
            /** Optional template for the WhatsApp leg outside the 24 h window. */
            TemplateRef template
    ) {
        /** Back-compat: an ingest body with no inline actions. */
        public IngestRequest(RecipientRef recipient, String body, List<MediaItem> media,
                             CorrelationRef correlation, String dedupKey, Map<String, Object> metadata) {
            this(recipient, body, media, correlation, dedupKey, metadata, null, null, null);
        }

        public IngestRequest(RecipientRef recipient, String body, List<MediaItem> media,
                             CorrelationRef correlation, String dedupKey, Map<String, Object> metadata,
                             List<ActionItem> actions) {
            this(recipient, body, media, correlation, dedupKey, metadata, actions, null, null);
        }

        public com.yayatechandinnovations.yayaagentic.channel.system.DeliveryRequest deliveryDomain() {
            var t = template == null || template.name() == null || template.name().isBlank() ? null
                    : new com.yayatechandinnovations.yayaagentic.channel.system.DeliveryRequest.TemplateRef(
                            template.name().trim(), template.language(), template.variables());
            if (delivery == null) {
                return new com.yayatechandinnovations.yayaagentic.channel.system.DeliveryRequest(null, null, t);
            }
            var channel = delivery.channel() == null || delivery.channel().isBlank() ? null
                    : com.yayatechandinnovations.yayaagentic.channel.system.DeliveryRequest.Channel.valueOf(
                            delivery.channel().trim().toUpperCase());
            return new com.yayatechandinnovations.yayaagentic.channel.system.DeliveryRequest(
                    channel, delivery.phoneE164(), t);
        }

        public List<MediaRef> mediaDomain() {
            return media == null ? List.of() : media.stream().map(MediaItem::toDomain).toList();
        }

        public List<com.yayatechandinnovations.yayaagentic.outreach.ActionItem> actionsDomain() {
            return actions == null ? List.of() : actions.stream().map(ActionItem::toDomain).toList();
        }
    }

    /** Reference to a pre-uploaded media asset (§10) — bytes are never in the envelope. */
    public record MediaItem(String assetId, String contentType, String filename, long sizeBytes) {
        public MediaRef toDomain() {
            return new MediaRef(assetId, contentType, filename, sizeBytes);
        }
    }

    /** An inline action button on the message (§D3); style ∈ {primary, danger, neutral}.
     *  Either {@code send} text or a bound {@code tool} (+ optional {@code ask}). */
    public record ActionItem(String label, String send, String style, ToolRef tool, AskRef ask) {
        public ActionItem(String label, String send, String style) {
            this(label, send, style, null, null);
        }

        public com.yayatechandinnovations.yayaagentic.outreach.ActionItem toDomain() {
            var boundTool = tool == null || tool.id() == null || tool.id().isBlank() ? null
                    : new com.yayatechandinnovations.yayaagentic.outreach.ActionItem.BoundTool(
                            tool.id().trim(), tool.args());
            var boundAsk = ask == null || ask.arg() == null || ask.arg().isBlank() ? null
                    : new com.yayatechandinnovations.yayaagentic.outreach.ActionItem.Ask(
                            ask.arg().trim(), ask.prompt(), ask.suggestions());
            return new com.yayatechandinnovations.yayaagentic.outreach.ActionItem(
                    label, send, style, boundTool, boundAsk, null);
        }
    }

    public record ToolRef(String id, Map<String, Object> args) {}

    public record AskRef(String arg, String prompt, List<String> suggestions) {}

    /** channel ∈ {CHAT, WHATSAPP}. */
    public record DeliveryRef(String channel, String phoneE164) {}

    public record TemplateRef(String name, String language, List<String> variables) {}

    /** kind ∈ {PRINCIPAL, ROLE, SESSION}; value is the subject / role / session id. */
    public record RecipientRef(String kind, String value) {
        public OutreachRecipient toDomain() {
            String k = kind == null ? "" : kind.trim().toUpperCase();
            String v = value == null ? "" : value.trim();
            return switch (k) {
                case "PRINCIPAL" -> new OutreachRecipient.ByPrincipal(v);
                case "ROLE" -> new OutreachRecipient.ByRole(v);
                case "SESSION" -> new OutreachRecipient.BySession(v);
                default -> throw new IllegalArgumentException("unknown recipient kind: " + kind);
            };
        }
    }

    public record CorrelationRef(String originatorName, String originatorRef, String subject) {
        public Correlation toDomain() {
            return new Correlation(originatorName, originatorRef, subject);
        }
    }

    public record IngestResponse(
            String id,
            String disposition,
            java.util.List<String> sessionIds,
            Instant receivedAt,
            String deliveredVia,
            java.util.List<DeliveryView> deliveries
    ) {
        public static IngestResponse of(OutreachReceipt r) {
            List<DeliveryView> views = r.deliveries().stream()
                    .map(d -> new DeliveryView(d.channel(), d.fallbackReason(), d.providerMessageId(),
                            d.status(), d.statusAt()))
                    .toList();
            return new IngestResponse(r.id().value(), r.disposition().name(),
                    r.sessionIds(), r.receivedAt(),
                    views.isEmpty() ? null : views.get(0).channel(), views);
        }
    }

    /** One resolved target's delivery (§14.1 / §14.2). */
    public record DeliveryView(String channel, String fallbackReason, String providerMessageId,
                               String status, Instant statusAt) {}

    /** {@code GET …/messages/{outreachId}} (§14.2). */
    public record StatusResponse(String id, String status, Instant receivedAt,
                                 List<DeliveryView> deliveries) {}
}
