package com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.channel.MediaTranscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Set;

/**
 * Default {@link MediaTranscriber} backed by OpenAI's Whisper. Loaded only
 * when {@code spring.ai.openai.api-key} is set (the same dependency used for
 * embeddings) — when unset the bean is absent and the dispatch worker
 * surfaces a typed "transcription unavailable" outcome.
 *
 * <p>We talk to Whisper's HTTP API directly (Spring AI's
 * {@code OpenAiAudioTranscriptionModel} bean isn't included in the starter
 * jar this project pulls, so we bypass it). Same endpoint, same model id,
 * same limits — just the lower-level transport.
 *
 * <p>See {@code whatsapp-channel-design.md} §9.5 — supported content types
 * cover the formats Twilio actually delivers (Opus from mobile, MP3 from
 * desktop), plus the WhatsApp Business API's audio variants.
 */
@Component
@ConditionalOnProperty(name = "spring.ai.openai.api-key")
public class WhisperMediaTranscriber implements MediaTranscriber {

    private static final Logger log = LoggerFactory.getLogger(WhisperMediaTranscriber.class);
    private static final String DEFAULT_MODEL = "whisper-1";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(60);

    /** Twilio's WhatsApp audio attachments arrive as one of these MIME types. */
    private static final Set<String> SUPPORTED = Set.of(
            "audio/ogg",
            "audio/ogg; codecs=opus",
            "audio/opus",
            "audio/mpeg",
            "audio/mp3",
            "audio/mp4",
            "audio/aac",
            "audio/x-m4a",
            "audio/wav",
            "audio/webm");

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper json;
    private final String apiKey;
    private final String baseUrl;
    private final String model;

    public WhisperMediaTranscriber(
            WebClient.Builder webClientBuilder,
            ObjectMapper json,
            @Value("${spring.ai.openai.api-key}") String apiKey,
            @Value("${spring.ai.openai.base-url:https://api.openai.com}") String baseUrl,
            @Value("${yaya.agentic.channels.whatsapp.transcription.model:whisper-1}") String model) {
        this.webClientBuilder = webClientBuilder;
        this.json = json;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model;
    }

    @Override
    public Set<String> supportedContentTypes() {
        return SUPPORTED;
    }

    @Override
    public TranscriptionResult transcribe(InboundMediaRef media,
                                          TranscriptionContext ctx) {
        if (media.sizeBytes() > ctx.maxBytes()) {
            throw new TranscriptionRejectedException("media_too_large",
                    "media is " + media.sizeBytes() + " bytes; limit is " + ctx.maxBytes());
        }
        WebClient client = webClientBuilder.build();

        // Step 1 — pull the bytes from the BSP CDN. Twilio's CDN requires
        // Basic auth using the account credentials Twilio gave the BSP
        // resolver.
        byte[] audio = client.get()
                .uri(media.mediaUrl())
                .header(HttpHeaders.AUTHORIZATION,
                        ctx.basicAuthHeader() == null ? "" : ctx.basicAuthHeader())
                .retrieve()
                .bodyToMono(byte[].class)
                .block(HTTP_TIMEOUT);
        if (audio == null || audio.length == 0) {
            throw new TranscriptionRejectedException("media_empty",
                    "BSP CDN returned no body for " + media.mediaUrl());
        }
        if (audio.length > ctx.maxBytes()) {
            throw new TranscriptionRejectedException("media_too_large",
                    "fetched audio exceeded byte cap");
        }

        // Step 2 — POST to OpenAI's transcription endpoint with the audio
        // bytes as multipart/form-data.
        MultipartBodyBuilder mp = new MultipartBodyBuilder();
        // OpenAI infers the audio format from the FILENAME EXTENSION, not the
        // multipart content type — an unknown extension (".dat") is a 400
        // "Invalid file format" even for perfectly valid Opus/OGG bytes.
        String uploadName = (ctx.messageSid() == null ? "audio" : ctx.messageSid())
                + "." + extensionFor(media.contentType());
        ByteArrayResource resource = new ByteArrayResource(audio) {
            @Override public String getFilename() {
                return uploadName;
            }
        };
        mp.part("file", resource).contentType(MediaType.parseMediaType(media.contentType()));
        mp.part("model", model);
        mp.part("response_format", "verbose_json");

        JsonNode resp;
        try {
            resp = WebClient.builder()
                    .baseUrl(baseUrl)
                    .build()
                    .post()
                    .uri("/v1/audio/transcriptions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(mp.build()))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(HTTP_TIMEOUT);
        } catch (Exception e) {
            log.warn("Whisper transcription transport failure for {}: {}",
                    ctx.messageSid(), e.getMessage());
            throw new TranscriptionRejectedException("transport_error", e.getMessage());
        }
        if (resp == null) {
            throw new TranscriptionRejectedException("empty_response",
                    "OpenAI transcription endpoint returned no body");
        }

        String text     = textOr(resp, "text", "");
        String language = textOr(resp, "language", null);
        double duration = resp.has("duration") ? resp.get("duration").asDouble(0) : 0;
        if (ctx.maxAudioDuration() != null
                && duration > ctx.maxAudioDuration().toSeconds()) {
            throw new TranscriptionRejectedException("audio_too_long",
                    "audio is " + (int) duration + "s; limit is "
                            + ctx.maxAudioDuration().toSeconds() + "s");
        }

        return new TranscriptionResult(
                text,
                language,
                1.0, // Whisper doesn't return a calibrated confidence
                Duration.ofSeconds((long) duration),
                model);
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        if (node == null) return fallback;
        JsonNode f = node.get(field);
        return f == null || f.isNull() ? fallback : f.asText(fallback);
    }

    /** Maps a Twilio audio content type to an extension OpenAI recognizes. */
    private static String extensionFor(String contentType) {
        if (contentType == null) return "ogg";
        String ct = contentType.toLowerCase(java.util.Locale.ROOT);
        if (ct.startsWith("audio/ogg") || ct.startsWith("audio/opus")) return "ogg";
        if (ct.startsWith("audio/mpeg") || ct.startsWith("audio/mp3"))  return "mp3";
        if (ct.startsWith("audio/mp4") || ct.startsWith("audio/aac")
                || ct.startsWith("audio/x-m4a"))                        return "m4a";
        if (ct.startsWith("audio/wav"))                                 return "wav";
        if (ct.startsWith("audio/webm"))                                return "webm";
        return "ogg";
    }

    /** Thrown by the transcriber when the input is invalid or fetch failed.
     *  The worker maps this to a templated user-facing error reply per
     *  §9.5 (no LLM round-trip). */
    public static class TranscriptionRejectedException extends RuntimeException {
        private final String code;
        public TranscriptionRejectedException(String code, String msg) {
            super(msg);
            this.code = code;
        }
        public String code() { return code; }
    }
}
