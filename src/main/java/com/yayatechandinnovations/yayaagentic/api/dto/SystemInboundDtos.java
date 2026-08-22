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
            List<ActionItem> actions
    ) {
        /** Back-compat: an ingest body with no inline actions. */
        public IngestRequest(RecipientRef recipient, String body, List<MediaItem> media,
                             CorrelationRef correlation, String dedupKey, Map<String, Object> metadata) {
            this(recipient, body, media, correlation, dedupKey, metadata, null);
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

    /** An inline action button on the message (§D3); style ∈ {primary, danger, neutral}. */
    public record ActionItem(String label, String send, String style) {
        public com.yayatechandinnovations.yayaagentic.outreach.ActionItem toDomain() {
            return new com.yayatechandinnovations.yayaagentic.outreach.ActionItem(label, send, style);
        }
    }

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
            Instant receivedAt
    ) {
        public static IngestResponse of(OutreachReceipt r) {
            return new IngestResponse(r.id().value(), r.disposition().name(),
                    r.sessionIds(), r.receivedAt());
        }
    }
}
