package com.yayatechandinnovations.yayaagentic.channel.whatsapp.templates;

/** Typed wrapper around the {@code whatsapp_templates.id} BIGSERIAL. */
public record TemplateId(long value) {

    public TemplateId {
        if (value <= 0) throw new IllegalArgumentException("TemplateId must be positive");
    }
}
