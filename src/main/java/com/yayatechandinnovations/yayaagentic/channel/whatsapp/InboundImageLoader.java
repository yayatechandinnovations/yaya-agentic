package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fetches inbound WhatsApp images from the BSP CDN so the model can see them
 * (vision) and tools can reference them by URL. Mirrors the voice-note path in
 * {@code WhisperMediaTranscriber}: Twilio's CDN wants the sender's Basic auth,
 * the bytes are never stored, and every limit fails soft — an oversized or
 * unreachable image is dropped with a log line, never a user-facing error.
 *
 * <p>Limits: {@code image/*} only, at most {@value #DEFAULT_MAX_IMAGES} images
 * per message and {@value #DEFAULT_MAX_BYTES} bytes each (both configurable).
 */
@Component
public class InboundImageLoader {

    public static final int DEFAULT_MAX_IMAGES = 4;
    public static final long DEFAULT_MAX_BYTES = 5L * 1024 * 1024;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(20);
    private static final Logger log = LoggerFactory.getLogger(InboundImageLoader.class);

    /** One fetched image: the engine takes {@code base64}; tools take {@code url}. */
    public record LoadedImage(String url, String contentType, long sizeBytes, String base64) {
        /** Metadata only — what rides {@code UserMessage.attachments["images"]}. */
        public Map<String, Object> metadata() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("url", url);
            m.put("contentType", contentType);
            m.put("sizeBytes", sizeBytes);
            return m;
        }

        /** The inline vision part the engine already understands ({@code chat.images}). */
        public Map<String, Object> inlinePart() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("contentType", contentType);
            m.put("base64", base64);
            return m;
        }
    }

    private final WebClient.Builder webClientBuilder;
    private final int maxImages;
    private final long maxBytes;

    public InboundImageLoader(
            WebClient.Builder webClientBuilder,
            @Value("${yaya.agentic.channels.whatsapp.images.max-per-message:" + DEFAULT_MAX_IMAGES + "}") int maxImages,
            @Value("${yaya.agentic.channels.whatsapp.images.max-bytes:" + DEFAULT_MAX_BYTES + "}") long maxBytes) {
        this.webClientBuilder = webClientBuilder;
        this.maxImages = maxImages;
        this.maxBytes = maxBytes;
    }

    public static boolean isImage(String contentType) {
        return contentType != null && contentType.trim().toLowerCase(Locale.ROOT).startsWith("image/");
    }

    /**
     * Downloads the first {@code maxImages} image items. {@code basicAuthHeader}
     * may be null (dev / sandbox CDN without auth). Never throws.
     */
    public List<LoadedImage> load(List<? extends MediaRef> media, String basicAuthHeader) {
        List<LoadedImage> out = new ArrayList<>();
        if (media == null || media.isEmpty()) return out;
        WebClient client = webClientBuilder.build();
        for (MediaRef m : media) {
            if (!isImage(m.contentType())) continue;
            if (out.size() >= maxImages) {
                log.info("Dropping extra inbound image (cap {} per message): {}", maxImages, m.mediaUrl());
                continue;
            }
            if (m.sizeBytes() > maxBytes) {
                log.info("Dropping inbound image over {} bytes (declared {}): {}", maxBytes, m.sizeBytes(), m.mediaUrl());
                continue;
            }
            try {
                byte[] bytes = client.get()
                        .uri(m.mediaUrl())
                        .header(HttpHeaders.AUTHORIZATION, basicAuthHeader == null ? "" : basicAuthHeader)
                        .retrieve()
                        .bodyToMono(byte[].class)
                        .block(HTTP_TIMEOUT);
                if (bytes == null || bytes.length == 0) {
                    log.info("Inbound image returned no body: {}", m.mediaUrl());
                    continue;
                }
                if (bytes.length > maxBytes) {
                    log.info("Dropping inbound image over {} bytes (fetched {}): {}", maxBytes, bytes.length, m.mediaUrl());
                    continue;
                }
                out.add(new LoadedImage(m.mediaUrl(), normalize(m.contentType()), bytes.length,
                        Base64.getEncoder().encodeToString(bytes)));
            } catch (RuntimeException e) {
                log.warn("Could not fetch inbound image {}: {}", m.mediaUrl(), e.getMessage());
            }
        }
        return out;
    }

    /** Twilio reports e.g. {@code image/jpeg}; strip any parameters so Spring AI parses a clean mime type. */
    static String normalize(String contentType) {
        int semi = contentType.indexOf(';');
        return (semi > 0 ? contentType.substring(0, semi) : contentType).trim().toLowerCase(Locale.ROOT);
    }

    /** Minimal view of an inbound media item (the worker's descriptor implements it). */
    public interface MediaRef {
        String mediaUrl();

        String contentType();

        long sizeBytes();
    }
}
