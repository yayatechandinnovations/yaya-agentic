package com.yayatechandinnovations.yayaagentic.channel.whatsapp.templates;

import java.time.Instant;
import java.util.List;

/**
 * One row of {@code whatsapp_templates}: a template of a WhatsApp account,
 * approved once and usable by every workspace that holds it
 * (whatsapp-shared-templates-design.md §3). The {@code bspContentSid}
 * (Twilio's Content API id) is populated on submission — sending a template
 * references it via {@code OutboundTemplate.providerHints.contentSid}.
 */
public record WhatsAppTemplate(
        TemplateId        id,
        /** {@link TemplateAccounts#PLATFORM}, or a workspace's own messaging account id. */
        String            accountKey,
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
        Instant           lastSyncAt,
        /** NONE, DOCUMENT or IMAGE (whatsapp-ops-integration-design.md §14.5). */
        String            headerKind,
        /** Quick-reply button labels, in order; their payloads are send-time variables. */
        List<String>      buttons,
        /** Left out of the console lists (§10); has no effect on sending. */
        Instant           hiddenAt,
        String            hiddenBy,
        /** More than one workspace holds it. Not computed on the send path. */
        boolean           shared,
        /**
         * 1-based placeholder the platform fills with the business name
         * (whatsapp-shared-sender-design.md §9.2); null when the template has
         * none, which keeps it off platform (shared) senders.
         */
        Integer           businessLabelVar
) {
    public WhatsAppTemplate {
        if (accountKey == null || accountKey.isBlank()) throw new IllegalArgumentException("accountKey required");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name required");
        if (locale == null || locale.isBlank()) throw new IllegalArgumentException("locale required");
        if (category == null) throw new IllegalArgumentException("category required");
        if (body == null || body.isBlank()) throw new IllegalArgumentException("body required");
        if (status == null) throw new IllegalArgumentException("status required");
        headerKind = headerKind == null || headerKind.isBlank() ? "NONE" : headerKind;
        buttons = buttons == null ? List.of() : List.copyOf(buttons);
    }

    /** Back-compat: a template without a business-name placeholder. */
    public WhatsAppTemplate(TemplateId id, String accountKey, String name, String locale, TemplateCategory category,
                            String body, int placeholderCount, String bspTemplateId, String bspContentSid,
                            TemplateStatus status, String rejectionReason, Instant submittedAt, Instant approvedAt,
                            Instant lastSyncAt, String headerKind, List<String> buttons, Instant hiddenAt,
                            String hiddenBy, boolean shared) {
        this(id, accountKey, name, locale, category, body, placeholderCount, bspTemplateId, bspContentSid,
                status, rejectionReason, submittedAt, approvedAt, lastSyncAt, headerKind, buttons, hiddenAt,
                hiddenBy, shared, null);
    }

    public boolean hidden() {
        return hiddenAt != null;
    }
}
