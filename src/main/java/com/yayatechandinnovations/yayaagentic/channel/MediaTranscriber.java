package com.yayatechandinnovations.yayaagentic.channel;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

/**
 * Transforms inbound audio media into text before the engine is invoked.
 * Designed-against {@code whatsapp-channel-design.md} §9.5 (Voice notes
 * and inbound media) — Whisper is the v1 default; Anthropic audio (when
 * GA) or a HIPAA-eligible alternative slots in behind this SPI without
 * engine changes.
 */
public interface MediaTranscriber {

    /** What MIME types this transcriber handles. The channel adapter picks
     *  the first transcriber whose set contains the inbound's content type. */
    Set<String> supportedContentTypes();

    TranscriptionResult transcribe(InboundMediaRef media, TranscriptionContext ctx);

    /**
     * Reference to a piece of inbound media. The BSP CDN URL is signed +
     * short-lived; the transcriber fetches through the platform's egress
     * allowlist (§16 SSRF protection note).
     */
    record InboundMediaRef(URI mediaUrl, String contentType, long sizeBytes) {

        public InboundMediaRef {
            if (mediaUrl == null) throw new IllegalArgumentException("mediaUrl required");
            if (contentType == null || contentType.isBlank()) {
                throw new IllegalArgumentException("contentType required");
            }
        }
    }

    /**
     * Per-call context — limits + auth pass-through for the transcriber.
     * {@code language} is the ISO-639-1 code the speech is expected in (the
     * tenant or session locale; null = let the model detect it).
     */
    record TranscriptionContext(
            String tenantId,
            String senderId,
            String messageSid,
            Duration maxAudioDuration,
            long maxBytes,
            String basicAuthHeader,
            String language
    ) {

        public TranscriptionContext {
            if (maxAudioDuration == null) maxAudioDuration = Duration.ofMinutes(5);
            if (maxBytes <= 0) maxBytes = 16L * 1024 * 1024;
        }

        public TranscriptionContext(String tenantId, String senderId, String messageSid,
                                    Duration maxAudioDuration, long maxBytes, String basicAuthHeader) {
            this(tenantId, senderId, messageSid, maxAudioDuration, maxBytes, basicAuthHeader, null);
        }
    }

    /**
     * Transcribe bytes already in hand (an upload, a download the channel made
     * itself). Default: unsupported — the WhatsApp/BSP contract fetches a URL.
     */
    default TranscriptionResult transcribeBytes(byte[] audio, String contentType, TranscriptionContext ctx) {
        throw new UnsupportedOperationException("this transcriber only takes a media URL");
    }

    record TranscriptionResult(
            String   text,
            String   detectedLanguage,
            double   confidence,
            Duration audioDuration,
            String   modelId
    ) {

        public TranscriptionResult {
            if (text == null) text = "";
        }
    }
}
