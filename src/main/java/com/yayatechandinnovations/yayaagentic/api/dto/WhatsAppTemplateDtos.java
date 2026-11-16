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

    /** The templates a hide or unhide applies to (whatsapp-shared-templates-design.md §10). */
    public record TemplateSelection(java.util.List<Long> templateIds) {}

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
            boolean          hidden
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
                    t.hidden());
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
            String           hiddenBy
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
                    t.hiddenBy());
        }
    }
}
