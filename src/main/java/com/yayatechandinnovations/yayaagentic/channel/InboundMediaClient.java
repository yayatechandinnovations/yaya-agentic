package com.yayatechandinnovations.yayaagentic.channel;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.time.Duration;

/**
 * The one HTTP client that downloads inbound media (a WhatsApp photo, voice
 * note or PDF from the BSP CDN). It carries its own in-memory buffer bound —
 * the global {@code spring.codec.max-in-memory-size} (4 MB) is below the
 * 5 MB image / 16 MB audio caps the channel intends, and raising it globally
 * would let every JSON endpoint buffer that much. {@code maxBytes} on a fetch
 * is the caller's own ceiling, enforced after the download too.
 */
@Component
public class InboundMediaClient {

    public static final long DEFAULT_BUFFER_BYTES = 16L * 1024 * 1024;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    private final WebClient client;

    public InboundMediaClient(WebClient.Builder builder,
                              @Value("${yaya.agentic.channels.media.max-in-memory-bytes:16777216}") long bufferBytes) {
        long bound = bufferBytes <= 0 ? DEFAULT_BUFFER_BYTES : bufferBytes;
        this.client = builder.clone()
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(c -> c.defaultCodecs().maxInMemorySize((int) Math.min(Integer.MAX_VALUE, bound)))
                        .build())
                .build();
    }

    /** A client with the default buffer, for code paths and tests that have no Spring builder. */
    public static InboundMediaClient direct() {
        return new InboundMediaClient(WebClient.builder(), DEFAULT_BUFFER_BYTES);
    }

    /** Thrown when the body is empty or over {@code maxBytes}. */
    public static class TooLargeException extends RuntimeException {
        public TooLargeException(String message) { super(message); }
    }

    /**
     * Downloads {@code url}; {@code basicAuthHeader} may be null. Throws on
     * transport errors, {@link TooLargeException} over the cap or when nothing
     * came back.
     */
    public byte[] fetch(URI url, String basicAuthHeader, long maxBytes) {
        return fetchNamed(url, basicAuthHeader, maxBytes).bytes();
    }

    /** A download with the name the server gave it ({@code Content-Disposition}), when any. */
    public record Fetched(byte[] bytes, String filename, String contentType) {}

    public Fetched fetchNamed(URI url, String basicAuthHeader, long maxBytes) {
        Fetched fetched = client.get()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, basicAuthHeader == null ? "" : basicAuthHeader)
                .retrieve()
                .toEntity(byte[].class)
                .map(entity -> new Fetched(entity.getBody(),
                        entity.getHeaders().getContentDisposition().getFilename(),
                        entity.getHeaders().getContentType() == null
                                ? null : entity.getHeaders().getContentType().toString()))
                .block(HTTP_TIMEOUT);
        byte[] bytes = fetched == null ? null : fetched.bytes();
        if (bytes == null || bytes.length == 0) {
            throw new TooLargeException("no body from " + url);
        }
        if (maxBytes > 0 && bytes.length > maxBytes) {
            throw new TooLargeException("media is " + bytes.length + " bytes; limit is " + maxBytes);
        }
        return fetched;
    }
}
