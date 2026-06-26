package com.yayatechandinnovations.yayaagentic.channel.whatsapp.templates;

/**
 * Lifecycle state of a WhatsApp template. Mirrors the CHECK constraint in
 * {@code V29__whatsapp_templates_and_optouts.sql} and the Meta review flow
 * the BSP relays.
 */
public enum TemplateStatus {
    DRAFT,
    SUBMITTED,
    APPROVED,
    REJECTED,
    PAUSED;

    public boolean canSubmit()   { return this == DRAFT || this == REJECTED; }
    public boolean canDispatch() { return this == APPROVED; }
}
