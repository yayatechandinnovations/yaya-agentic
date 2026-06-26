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
            Instant          approvedAt
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
                    t.approvedAt());
        }
    }
}
