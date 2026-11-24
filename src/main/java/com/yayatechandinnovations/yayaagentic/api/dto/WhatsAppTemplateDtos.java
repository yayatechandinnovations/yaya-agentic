package com.yayatechandinnovations.yayaagentic.api.dto;

import com.yayatechandinnovations.yayaagentic.channel.whatsapp.templates.TemplateCategory;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.templates.TemplateStatus;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.templates.WhatsAppTemplate;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.templates.WhatsAppTemplateService;

import java.time.Instant;

public final class WhatsAppTemplateDtos {

    private WhatsAppTemplateDtos() {}

    public record TemplateRequest(
            String           name,
            String           locale,
            TemplateCategory category,
            String           body,
            /** whatsapp-shared-sender-design.md §9.2 — null keeps the template off platform senders. */
            Integer          businessLabelVar
    ) {
        public TemplateRequest(String name, String locale, TemplateCategory category, String body) {
            this(name, locale, category, body, null);
        }
    }

    /** Declarative upsert by name (whatsapp-ops-integration-design.md §14.5/§14.6). */
    public record TemplateDeclaration(
            String           name,
            String           language,
            TemplateCategory category,
            String           header,
            String           body,
            java.util.List<String> buttons,
            Samples          samples,
            /** whatsapp-shared-sender-design.md §9.2 — the placeholder the platform fills with the business name. */
            Integer          businessLabelVar
    ) {
        public TemplateDeclaration(String name, String language, TemplateCategory category, String header,
                                   String body, java.util.List<String> buttons, Samples samples) {
            this(name, language, category, header, body, buttons, samples, null);
        }
    }

    public record Samples(java.util.List<String> body, String mediaFilename) {}

    public record TemplateUpdate(
            String           body,
            TemplateCategory category,
            /** Null keeps the stored value; 0 clears it (§9.2). */
            Integer          businessLabelVar
    ) {
        public TemplateUpdate(String body, TemplateCategory category) {
            this(body, category, null);
        }
    }

    /** The templates a hide or unhide applies to (whatsapp-shared-templates-design.md §10). */
    public record TemplateSelection(java.util.List<Long> templateIds) {}

    /** Every listed workspace gets every listed template (whatsapp-shared-templates-design.md §10.5). */
    public record TemplateShare(java.util.List<Long> templateIds, java.util.List<String> tenantIds) {}

    /**
     * A template as one workspace sees it through one of its senders.
     * {@code shared}: other workspaces hold it too (they are never named here).
     * {@code hidden}: left out of the console lists; who hid it is not said here.
     */
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
            Instant          lastSyncAt,
            boolean          shared,
            boolean          hidden,
            Integer          businessLabelVar
    ) {
        public static TemplateResponse of(WhatsAppTemplate t, String tenantId, String senderId) {
            return new TemplateResponse(
                    t.id().value(),
                    tenantId,
                    senderId,
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
                    t.lastSyncAt(),
                    t.shared(),
                    t.hidden(),
                    t.businessLabelVar());
        }
    }

    /* ---- platform-admin view (whatsapp-shared-templates-design.md §5) ---- */

    /** A WhatsApp account that carries templates; {@code tenantId} is null for the platform account. */
    public record AccountResponse(
            String accountKey,
            String tenantId,
            String label,
            String status,
            long   templateCount,
            long   hiddenCount
    ) {
        public static AccountResponse of(WhatsAppTemplateService.Account a) {
            return new AccountResponse(a.accountKey(), a.tenantId(), a.label(), a.status(), a.templateCount(),
                    a.hiddenCount());
        }
    }

    public record HolderResponse(String tenantId, String grantedBy, Instant grantedAt) {}

    /** A template of an account with every workspace that holds it. */
    public record AccountTemplateResponse(
            long             id,
            String           accountKey,
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
            Instant          lastSyncAt,
            java.util.List<HolderResponse> holders,
            boolean          hidden,
            Instant          hiddenAt,
            String           hiddenBy,
            Integer          businessLabelVar
    ) {
        public static AccountTemplateResponse of(WhatsAppTemplateService.AccountTemplate at) {
            WhatsAppTemplate t = at.template();
            return new AccountTemplateResponse(
                    t.id().value(),
                    t.accountKey(),
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
                    t.lastSyncAt(),
                    at.holders().stream()
                            .map(h -> new HolderResponse(h.tenantId(), h.grantedBy(), h.grantedAt()))
                            .toList(),
                    t.hidden(),
                    t.hiddenAt(),
                    t.hiddenBy(),
                    t.businessLabelVar());
        }
    }
}
