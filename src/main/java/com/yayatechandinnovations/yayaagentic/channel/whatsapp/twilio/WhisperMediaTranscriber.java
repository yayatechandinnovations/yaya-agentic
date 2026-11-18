package com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.channel.InboundMediaClient;
import com.yayatechandinnovations.yayaagentic.channel.MediaTranscriber;
import com.yayatechandinnovations.yayaagentic.channel.chat.media.AudioDurations;
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

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Default {@link MediaTranscriber} backed by OpenAI's transcription endpoint.
 * Loaded only when {@code spring.ai.openai.api-key} is set (the same
 * dependency used for embeddings) — when unset the bean is absent and the
 * channels answer with a fixed "I could not listen to that" reply.
 *
 * <p>We talk to the HTTP API directly (Spring AI's audio model bean is not in
 * the starter this project pulls). The request carries the expected
 * {@code language} and a domain-vocabulary {@code prompt}
 * ({@code yaya.agentic.transcribe.prompt}) that biases recognition towards
 * the words the business uses (assistant-channels design §4.2 V3). The model
 * is {@code yaya.agentic.transcribe.model}: {@code whisper-1} (default,
 * answers {@code verbose_json} with the audio duration) or
 * {@code gpt-4o-mini-transcribe} / {@code gpt-4o-transcribe}, which only take
 * {@code json} — the duration then comes from the container
 * ({@link AudioDurations}).
 *
 * <p>Supported content types cover the formats Twilio actually delivers (Opus
 * from mobile, MP3 from desktop) plus the WhatsApp Business API's audio
 * variants and browser recordings.
 */
@Component
@ConditionalOnProperty(name = "spring.ai.openai.api-key")
public class WhisperMediaTranscriber implements MediaTranscriber {

    private static final Logger log = LoggerFactory.getLogger(WhisperMediaTranscriber.class);
    public static final String DEFAULT_MODEL = "whisper-1";
    public static final String DEFAULT_PROMPT =
            "quiniela, palé, tripleta, estación, banca, cuadre, anular, premio, loto";
    public static final String DEFAULT_LANGUAGE = "es";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(60);

    /** Twilio's WhatsApp audio attachments arrive as one of these MIME types. */
    static final Set<String> SUPPORTED = Set.of(
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

    private final InboundMediaClient mediaClient;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final String prompt;
    private final String defaultLanguage;
    private final WebClient.Builder apiClientBuilder;

    public WhisperMediaTranscriber(
            InboundMediaClient mediaClient,
            ObjectMapper json,
            @Value("${spring.ai.openai.api-key}") String apiKey,
            @Value("${spring.ai.openai.base-url:https://api.openai.com}") String baseUrl,
            @Value("${yaya.agentic.transcribe.model:${yaya.agentic.channels.whatsapp.transcription.model:whisper-1}}") String model,
            @Value("${yaya.agentic.transcribe.prompt:" + DEFAULT_PROMPT + "}") String prompt,
            @Value("${yaya.agentic.transcribe.language:" + DEFAULT_LANGUAGE + "}") String defaultLanguage) {
        this.mediaClient = mediaClient;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model.trim();
        this.prompt = prompt == null ? "" : prompt.trim();
        this.defaultLanguage = defaultLanguage == null || defaultLanguage.isBlank()
                ? DEFAULT_LANGUAGE : defaultLanguage.trim();
        this.apiClientBuilder = WebClient.builder().baseUrl(this.baseUrl);
    }

    public String model() {
        return model;
    }

    @Override
    public Set<String> supportedContentTypes() {
        return SUPPORTED;
    }

    @Override
    public TranscriptionResult transcribe(InboundMediaRef media, TranscriptionContext ctx) {
        if (media.sizeBytes() > ctx.maxBytes()) {
            throw new TranscriptionRejectedException("media_too_large",
                    "media is " + media.sizeBytes() + " bytes; limit is " + ctx.maxBytes());
        }
        // Pull the bytes from the BSP CDN. Twilio's CDN requires Basic auth
        // using the account credentials Twilio gave the BSP resolver.
        byte[] audio;
        try {
            audio = mediaClient.fetch(media.mediaUrl(), ctx.basicAuthHeader(), ctx.maxBytes());
        } catch (InboundMediaClient.TooLargeException e) {
            throw new TranscriptionRejectedException(
                    e.getMessage().startsWith("no body") ? "media_empty" : "media_too_large", e.getMessage());
        } catch (RuntimeException e) {
            throw new TranscriptionRejectedException("transport_error", e.getMessage());
        }
        return transcribeBytes(audio, media.contentType(), ctx);
    }

    @Override
    public TranscriptionResult transcribeBytes(byte[] audio, String contentType, TranscriptionContext ctx) {
        if (audio == null || audio.length == 0) {
            throw new TranscriptionRejectedException("media_empty", "no audio bytes");
        }
        if (audio.length > ctx.maxBytes()) {
            throw new TranscriptionRejectedException("media_too_large",
                    "audio is " + audio.length + " bytes; limit is " + ctx.maxBytes());
        }
        // The container knows the length before any money is spent.
        OptionalInt containerSeconds = AudioDurations.parse(audio, contentType);
        long maxSeconds = ctx.maxAudioDuration() == null ? Long.MAX_VALUE : ctx.maxAudioDuration().toSeconds();
        if (containerSeconds.isPresent() && containerSeconds.getAsInt() > maxSeconds) {
            throw new TranscriptionRejectedException("audio_too_long",
                    "audio is " + containerSeconds.getAsInt() + "s; limit is " + maxSeconds + "s");
        }

        MultipartBodyBuilder mp = new MultipartBodyBuilder();
        // OpenAI infers the audio format from the FILENAME EXTENSION, not the
        // multipart content type — an unknown extension (".dat") is a 400
        // "Invalid file format" even for perfectly valid Opus/OGG bytes.
        String uploadName = (ctx.messageSid() == null ? "audio" : ctx.messageSid())
                + "." + extensionFor(contentType);
        ByteArrayResource resource = new ByteArrayResource(audio) {
            @Override public String getFilename() {
                return uploadName;
            }
        };
        mp.part("file", resource).contentType(MediaType.parseMediaType(bare(contentType)));
        mp.part("model", model);
        mp.part("response_format", responseFormat());
        String language = languageFor(ctx);
        if (language != null) mp.part("language", language);
        if (!prompt.isBlank()) mp.part("prompt", prompt);

        JsonNode resp;
        try {
            resp = apiClientBuilder.build()
                    .post()
                    .uri("/v1/audio/transcriptions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(mp.build()))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(HTTP_TIMEOUT);
        } catch (Exception e) {
            log.warn("transcription transport failure for {}: {}", ctx.messageSid(), e.getMessage());
            throw new TranscriptionRejectedException("transport_error", e.getMessage());
        }
        if (resp == null) {
            throw new TranscriptionRejectedException("empty_response",
                    "OpenAI transcription endpoint returned no body");
        }

        String text = textOr(resp, "text", "");
        String detected = textOr(resp, "language", language);
        double reported = resp.has("duration") ? resp.get("duration").asDouble(0) : 0;
        int seconds = reported > 0 ? (int) Math.ceil(reported) : containerSeconds.orElse(0);
        if (seconds > maxSeconds) {
            throw new TranscriptionRejectedException("audio_too_long",
                    "audio is " + seconds + "s; limit is " + maxSeconds + "s");
        }
        return new TranscriptionResult(
                text,
                detected,
                1.0, // the endpoint returns no calibrated confidence
                Duration.ofSeconds(seconds),
                model);
    }

    /** {@code verbose_json} (duration + language) exists only on whisper-1. */
    String responseFormat() {
        return model.startsWith("whisper") ? "verbose_json" : "json";
    }

    /** ISO-639-1 from the context's locale ({@code es-DO} → {@code es}), else the configured default. */
    String languageFor(TranscriptionContext ctx) {
        String raw = ctx == null ? null : ctx.language();
        if (raw == null || raw.isBlank()) raw = defaultLanguage;
        if (raw == null || raw.isBlank()) return null;
        String code = raw.trim().toLowerCase(Locale.ROOT);
        int cut = code.indexOf('-');
        if (cut < 0) cut = code.indexOf('_');
        if (cut > 0) code = code.substring(0, cut);
        return code.length() == 2 || code.length() == 3 ? code : null;
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        if (node == null) return fallback;
        JsonNode f = node.get(field);
        return f == null || f.isNull() ? fallback : f.asText(fallback);
    }

    private static String bare(String contentType) {
        if (contentType == null || contentType.isBlank()) return "application/octet-stream";
        int semi = contentType.indexOf(';');
        return (semi > 0 ? contentType.substring(0, semi) : contentType).trim();
    }

    /** Maps an audio content type to an extension OpenAI recognizes. */
    static String extensionFor(String contentType) {
        if (contentType == null) return "ogg";
        String ct = contentType.toLowerCase(Locale.ROOT);
        if (ct.startsWith("audio/ogg") || ct.startsWith("audio/opus")
                || ct.startsWith("application/ogg") || ct.startsWith("audio/vorbis")
                || ct.startsWith("audio/x-opus"))                              return "ogg";
        if (ct.startsWith("audio/mpeg") || ct.startsWith("audio/mp3"))         return "mp3";
        if (ct.startsWith("audio/mp4") || ct.startsWith("audio/aac")
                || ct.startsWith("audio/x-m4a"))                               return "m4a";
        if (ct.startsWith("audio/wav") || ct.startsWith("audio/x-wav"))        return "wav";
        if (ct.startsWith("audio/webm") || ct.startsWith("video/webm"))        return "webm";
        return "ogg";
    }

    /** Fetch a URL into bytes with the same client; for callers that want the audio kept. */
    public byte[] download(URI url, String basicAuthHeader, long maxBytes) {
        return mediaClient.fetch(url, basicAuthHeader, maxBytes);
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
