package com.yayatechandinnovations.yayaagentic.channel;

import java.time.Instant;
import java.util.List;

/**
 * Result of {@link MessagingChannelProvider#syncTemplates} — the periodic
 * pull of template approval status from the BSP. Mirrors the
 * {@code whatsapp_templates} table shape (§11.1) but flattened to the
 * BSP-facing view (the BSP doesn't see our internal template row ID).
 */
public record TemplateSyncResult(
        SenderRef           senderRef,
        Instant             polledAt,
        List<TemplateState> templates
) {

    public TemplateSyncResult {
        if (senderRef == null) {
            throw new IllegalArgumentException("senderRef required");
        }
        if (polledAt == null) {
            throw new IllegalArgumentException("polledAt required");
        }
        templates = templates == null ? List.of() : List.copyOf(templates);
    }

    public record TemplateState(
            String name,
            String locale,
            String bspTemplateId,
            String status,
            String rejectionReason
    ) {}
}
