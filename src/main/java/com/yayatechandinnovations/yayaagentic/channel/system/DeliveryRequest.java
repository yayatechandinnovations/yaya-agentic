package com.yayatechandinnovations.yayaagentic.channel.system;

import java.util.List;

/**
 * Optional delivery preferences on an ingest envelope
 * (whatsapp-ops-integration-design.md §14.1). The recipient stays the chat
 * principal — it is the fallback when the preferred channel cannot carry the
 * message.
 *
 * @param channel   {@code CHAT} (default) or {@code WHATSAPP}
 * @param phoneE164 the recipient's WhatsApp number; required for WHATSAPP
 * @param template  the approved template to use outside the 24 h window
 */
public record DeliveryRequest(Channel channel, String phoneE164, TemplateRef template) {

    public enum Channel { CHAT, WHATSAPP }

    public static DeliveryRequest chat() {
        return new DeliveryRequest(Channel.CHAT, null, null);
    }

    public DeliveryRequest {
        channel = channel == null ? Channel.CHAT : channel;
        phoneE164 = phoneE164 == null || phoneE164.isBlank() ? null : phoneE164.trim();
    }

    public boolean wantsWhatsApp() {
        return channel == Channel.WHATSAPP;
    }

    /** Body variables only ({{1}}…{{n}}); the platform appends button and media variables. */
    public record TemplateRef(String name, String language, List<String> variables) {
        public TemplateRef {
            language = language == null || language.isBlank() ? "es" : language.trim();
            variables = variables == null ? List.of() : List.copyOf(variables);
        }
    }
}
