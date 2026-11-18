package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.yayatechandinnovations.yayaagentic.channel.InboundMediaClient;
import com.yayatechandinnovations.yayaagentic.channel.chat.media.ImageCodes;
import com.yayatechandinnovations.yayaagentic.channel.chat.media.ImageHygiene;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
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
 * HEIC/HEIF is not loaded (the worker answers with a fixed reply); JPEG/PNG
 * go through {@link ImageHygiene} (orientation, EXIF strip, ≤ 1,600 px) and
 * every image is read for QR / barcodes ({@link ImageCodes}).
 */
@Component
public class InboundImageLoader {

    public static final int DEFAULT_MAX_IMAGES = 4;
    public static final long DEFAULT_MAX_BYTES = 5L * 1024 * 1024;
    private static final Logger log = LoggerFactory.getLogger(InboundImageLoader.class);

    /** One fetched image: the engine takes {@code base64}; tools take {@code url};
     *  {@code codes} are the QR / barcodes read from it. */
    public record LoadedImage(String url, String contentType, long sizeBytes, String base64,
                              List<ImageCodes.Code> codes) {
        public LoadedImage {
            codes = codes == null ? List.of() : List.copyOf(codes);
        }

        public LoadedImage(String url, String contentType, long sizeBytes, String base64) {
            this(url, contentType, sizeBytes, base64, List.of());
        }

        /** Metadata only — what rides {@code UserMessage.attachments["images"]}. */
        public Map<String, Object> metadata() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("url", url);
            m.put("contentType", contentType);
            m.put("sizeBytes", sizeBytes);
            if (!codes.isEmpty()) m.put("codes", codes.stream().map(ImageCodes.Code::toMap).toList());
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

    private final InboundMediaClient mediaClient;
    private final int maxImages;
    private final long maxBytes;

    public InboundImageLoader(
            InboundMediaClient mediaClient,
            @Value("${yaya.agentic.channels.whatsapp.images.max-per-message:" + DEFAULT_MAX_IMAGES + "}") int maxImages,
            @Value("${yaya.agentic.channels.whatsapp.images.max-bytes:" + DEFAULT_MAX_BYTES + "}") long maxBytes) {
        this.mediaClient = mediaClient;
        this.maxImages = maxImages;
        this.maxBytes = maxBytes;
    }

    /** A photo the model can take: {@code image/*} that is not HEIC/HEIF. */
    public static boolean isImage(String contentType) {
        return contentType != null && contentType.trim().toLowerCase(Locale.ROOT).startsWith("image/")
                && !isHeic(contentType);
    }

    public static boolean isHeic(String contentType) {
        return ImageHygiene.isHeic(contentType);
    }

    /**
     * Downloads the first {@code maxImages} image items. {@code basicAuthHeader}
     * may be null (dev / sandbox CDN without auth). Never throws.
     */
    public List<LoadedImage> load(List<? extends MediaRef> media, String basicAuthHeader) {
        List<LoadedImage> out = new ArrayList<>();
        if (media == null || media.isEmpty()) return out;
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
                byte[] bytes = mediaClient.fetch(URI.create(m.mediaUrl()), basicAuthHeader, maxBytes);
                ImageHygiene.Normalized clean = ImageHygiene.normalize(bytes, normalize(m.contentType()));
                List<ImageCodes.Code> codes = clean.image() != null
                        ? ImageCodes.decode(clean.image()) : ImageCodes.decode(clean.bytes());
                out.add(new LoadedImage(m.mediaUrl(), normalize(clean.contentType()), clean.bytes().length,
                        Base64.getEncoder().encodeToString(clean.bytes()), codes));
            } catch (InboundMediaClient.TooLargeException e) {
                log.info("Dropping inbound image: {} ({})", m.mediaUrl(), e.getMessage());
            } catch (ImageHygiene.ImageTooLargeException e) {
                // Never decoded: the header alone said it is over the ceiling.
                log.info("Dropping inbound image over the decode ceiling: {} ({})", m.mediaUrl(), e.getMessage());
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
