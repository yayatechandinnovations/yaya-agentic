package com.yayatechandinnovations.yayaagentic.channel.whatsapp.templates;

import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.time.Instant;

/**
 * One row of {@code whatsapp_templates}. The {@code bspContentSid} (Twilio's
 * Content API id) is populated post-approval — sending a template references
 * it via {@code OutboundTemplate.providerHints.contentSid}.
 */
public record WhatsAppTemplate(
        TemplateId        id,
        Ids.TenantId      tenant,
        SenderId          sender,
        String            name,
        String            locale,
        TemplateCategory  category,
        String            body,
        int               placeholderCount,
        String            bspTemplateId,
        String            bspContentSid,
        TemplateStatus    status,
        String            rejectionReason,
        Instant           submittedAt,
        Instant           approvedAt,
        Instant           lastSyncAt
) {
    public WhatsAppTemplate {
        if (tenant == null) throw new IllegalArgumentException("tenant required");
        if (sender == null) throw new IllegalArgumentException("sender required");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name required");
        if (locale == null || locale.isBlank()) throw new IllegalArgumentException("locale required");
        if (category == null) throw new IllegalArgumentException("category required");
        if (body == null || body.isBlank()) throw new IllegalArgumentException("body required");
        if (status == null) throw new IllegalArgumentException("status required");
    }
}
