package com.yayatechandinnovations.yayaagentic.api.dto;

import com.yayatechandinnovations.yayaagentic.channel.whatsapp.templates.TemplateCategory;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.templates.TemplateStatus;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.templates.WhatsAppTemplate;

import java.time.Instant;

public final class WhatsAppTemplateDtos {

    private WhatsAppTemplateDtos() {}

    public record TemplateRequest(
            String           name,
            String           locale,
            TemplateCategory category,
            String           body
    ) {}

    /** Declarative upsert by name (whatsapp-ops-integration-design.md §14.5/§14.6). */
    public record TemplateDeclaration(
            String           name,
            String           language,
            TemplateCategory category,
            String           header,
            String           body,
            java.util.List<String> buttons,
            Samples          samples
    ) {}

    public record Samples(java.util.List<String> body, String mediaFilename) {}

    public record TemplateUpdate(
            String           body,
            TemplateCategory category
    ) {}

    public record TemplateResponse(
            long             id,
            String           tenantId,
            String           senderId,
            String           name,
            String           locale,
            TemplateCategory category,
            String           body,
            int              placeholderCount,
            TemplateStatus   status,
            String           rejectionReason,
            Instant          submittedAt,
            Instant          approvedAt,
            String           headerKind,
            java.util.List<String> buttons,
            String           contentSid,
            Instant          lastSyncAt
    ) {

        public static TemplateResponse of(WhatsAppTemplate t) {
            return new TemplateResponse(
                    t.id().value(),
                    t.tenant().value(),
                    t.sender().value(),
                    t.name(),
                    t.locale(),
                    t.category(),
                    t.body(),
                    t.placeholderCount(),
                    t.status(),
                    t.rejectionReason(),
                    t.submittedAt(),
                    t.approvedAt(),
                    t.headerKind(),
                    t.buttons(),
                    t.bspContentSid(),
                    t.lastSyncAt());
        }
    }
}
