package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.yayatechandinnovations.yayaagentic.channel.chat.media.ImageHygiene;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Sorts an inbound WhatsApp message's media into what the assistant can take
 * (photos, voice notes, PDFs) and what it cannot, and names the fixed reply
 * each refused kind gets — so nothing is silently dropped
 * (assistant-channels design §3.3, §3.6, §4.2). Pure; the worker sends the
 * replies and runs the rest.
 */
final class InboundMediaGate {

    static final String UNSUPPORTED_REPLY =
            "Todavía no puedo abrir ese tipo de archivo. Mándame una foto, un PDF o una nota de voz.";
    static final String HEIC_REPLY = ImageHygiene.HEIC_HINT;
    static final String VOICE_UNAVAILABLE_REPLY = "No pude escuchar la nota de voz, ¿me lo escribes?";
    static final String VOICE_TOO_LONG_REPLY = "La nota de voz es muy larga (máximo 2 minutos)";
    static final String VOICE_QUOTA_REPLY = "Este mes se agotaron los minutos de voz; escríbeme.";

    /** The user text a media-only message gets, so the turn runs. */
    static final String PHOTO_PLACEHOLDER = "(foto)";

    private InboundMediaGate() {}

    record Plan(List<InboundDispatchWorker.MediaDescriptor> images,
                List<InboundDispatchWorker.MediaDescriptor> audio,
                List<InboundDispatchWorker.MediaDescriptor> documents,
                List<String> fixedReplies) {
        boolean hasMedia() { return !images.isEmpty() || !audio.isEmpty() || !documents.isEmpty(); }
    }

    /**
     * @param transcriberPresent whether a transcriber bean exists
     * @param voiceAllowed       whether the tenant still has voice seconds this month —
     *                           asked only when a voice note is present (it costs SQL)
     */
    static Plan classify(List<InboundDispatchWorker.MediaDescriptor> media, Map<String, Object> raw,
                         boolean transcriberPresent, boolean voiceAllowed) {
        return classify(media, raw, transcriberPresent, () -> voiceAllowed);
    }

    static Plan classify(List<InboundDispatchWorker.MediaDescriptor> media, Map<String, Object> raw,
                         boolean transcriberPresent, java.util.function.BooleanSupplier voiceAllowed) {
        List<InboundDispatchWorker.MediaDescriptor> images = new ArrayList<>();
        List<InboundDispatchWorker.MediaDescriptor> audio = new ArrayList<>();
        List<InboundDispatchWorker.MediaDescriptor> documents = new ArrayList<>();
        Set<String> replies = new LinkedHashSet<>();
        for (InboundDispatchWorker.MediaDescriptor m : media == null ? List.<InboundDispatchWorker.MediaDescriptor>of() : media) {
            String ct = bare(m.contentType());
            if (ImageHygiene.isHeic(ct)) {
                replies.add(HEIC_REPLY);
            } else if (ct.equals("image/webp")) {
                // WhatsApp stickers arrive as WebP; photos arrive as JPEG.
                replies.add(UNSUPPORTED_REPLY);
            } else if (ct.startsWith("image/")) {
                images.add(m);
            } else if (ct.startsWith("audio/")) {
                if (!transcriberPresent) replies.add(VOICE_UNAVAILABLE_REPLY);
                else if (!voiceAllowed.getAsBoolean()) replies.add(VOICE_QUOTA_REPLY);
                else audio.add(m);
            } else if (ct.equals("application/pdf")) {
                documents.add(m);
            } else {
                // video, contacts (text/vcard), anything else
                replies.add(UNSUPPORTED_REPLY);
            }
        }
        // A shared location has no media item: Twilio sends Latitude/Longitude.
        if (raw != null && raw.get("Latitude") instanceof String lat && !lat.isBlank()
                && media != null && media.isEmpty()) {
            replies.add(UNSUPPORTED_REPLY);
        }
        return new Plan(images, audio, documents, List.copyOf(replies));
    }

    static String bare(String contentType) {
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        int semi = ct.indexOf(';');
        return (semi >= 0 ? ct.substring(0, semi) : ct).trim();
    }
}
