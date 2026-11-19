package com.yayatechandinnovations.yayaagentic.channel.system;

import java.util.List;

/**
 * Optional delivery preferences on an ingest envelope
 * (whatsapp-ops-integration-design.md §14.1). The recipient stays the chat
 * principal — it is the fallback when the preferred channel cannot carry the
 * message, unless {@code fallback} is {@link Fallback#NONE}: then a WhatsApp
 * leg that cannot go out is recorded as FAILED and nothing is written to any
 * chat (pos-ticket-whatsapp-share-design.md §8 P2 — customers have no inbox).
 *
 * @param channel   {@code CHAT} (default) or {@code WHATSAPP}
 * @param phoneE164 the recipient's WhatsApp number; required for WHATSAPP
 * @param template  the approved template to use outside the 24 h window
 * @param fallback  {@code CHAT} (default) or {@code NONE}
 */
public record DeliveryRequest(Channel channel, String phoneE164, TemplateRef template, Fallback fallback) {

    public enum Channel { CHAT, WHATSAPP }

    /** What happens when the WhatsApp leg cannot deliver. */
    public enum Fallback { CHAT, NONE }

    public static DeliveryRequest chat() {
        return new DeliveryRequest(Channel.CHAT, null, null, Fallback.CHAT);
    }

    /** Back-compat: chat fallback. */
    public DeliveryRequest(Channel channel, String phoneE164, TemplateRef template) {
        this(channel, phoneE164, template, Fallback.CHAT);
    }

    public DeliveryRequest {
        channel = channel == null ? Channel.CHAT : channel;
        phoneE164 = phoneE164 == null || phoneE164.isBlank() ? null : phoneE164.trim();
        fallback = fallback == null ? Fallback.CHAT : fallback;
    }

    public boolean wantsWhatsApp() {
        return channel == Channel.WHATSAPP;
    }

    /** False only for a WhatsApp request that asked for no chat fallback. */
    public boolean wantsChatFallback() {
        return !(wantsWhatsApp() && fallback == Fallback.NONE);
    }

    /** Body variables only ({{1}}…{{n}}); the platform appends button and media variables. */
    public record TemplateRef(String name, String language, List<String> variables) {
        public TemplateRef {
            language = language == null || language.isBlank() ? "es" : language.trim();
            variables = variables == null ? List.of() : List.copyOf(variables);
        }
    }
}
