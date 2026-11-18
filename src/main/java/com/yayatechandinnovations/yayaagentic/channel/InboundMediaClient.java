package com.yayatechandinnovations.yayaagentic.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The one HTTP client that downloads inbound media (a WhatsApp photo, voice
 * note or PDF from the BSP CDN). It carries its own in-memory buffer bound —
 * the global {@code spring.codec.max-in-memory-size} (4 MB) is below the
 * 5 MB image / 16 MB audio caps the channel intends, and raising it globally
 * would let every JSON endpoint buffer that much. {@code maxBytes} on a fetch
 * is the caller's own ceiling, enforced after the download too.
 *
 * <p>Only the BSP's own hosts are fetched: an inbound payload names the media
 * URL, and a forged webhook must not turn this server into a fetcher of
 * arbitrary addresses. The default allow-list is Twilio's ({@code *.twilio.com},
 * {@code *.twiliocdn.com}) over HTTPS; {@code yaya.agentic.channels.media.allowed-hosts}
 * extends it (loopback hosts may use plain HTTP, for test stubs).
 */
@Component
public class InboundMediaClient {

    private static final Logger log = LoggerFactory.getLogger(InboundMediaClient.class);

    public static final long DEFAULT_BUFFER_BYTES = 16L * 1024 * 1024;
    /** The hosts Twilio serves media from: the API itself and its media CDN. */
    public static final Set<String> TWILIO_HOSTS = Set.of("twilio.com", "twiliocdn.com");
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    private final WebClient client;
    private final Set<String> allowedHosts;

    @org.springframework.beans.factory.annotation.Autowired
    public InboundMediaClient(WebClient.Builder builder,
                              @Value("${yaya.agentic.channels.media.max-in-memory-bytes:16777216}") long bufferBytes,
                              @Value("${yaya.agentic.channels.media.allowed-hosts:}") String allowedHosts) {
        this(builder, bufferBytes, parseHosts(allowedHosts));
    }

    InboundMediaClient(WebClient.Builder builder, long bufferBytes, Set<String> allowedHosts) {
        long bound = bufferBytes <= 0 ? DEFAULT_BUFFER_BYTES : bufferBytes;
        this.client = builder.clone()
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(c -> c.defaultCodecs().maxInMemorySize((int) Math.min(Integer.MAX_VALUE, bound)))
                        .build())
                .build();
        Set<String> hosts = new LinkedHashSet<>(TWILIO_HOSTS);
        hosts.addAll(allowedHosts);
        this.allowedHosts = Set.copyOf(hosts);
    }

    /** A client with the default buffer and Twilio's hosts only, for code paths that have no Spring builder. */
    public static InboundMediaClient direct() {
        return new InboundMediaClient(WebClient.builder(), DEFAULT_BUFFER_BYTES, Set.of());
    }

    /** {@link #direct()} plus {@code extraHosts} (a test stub on {@code localhost}, typically). */
    public static InboundMediaClient direct(String... extraHosts) {
        return new InboundMediaClient(WebClient.builder(), DEFAULT_BUFFER_BYTES, parseHosts(String.join(",", extraHosts)));
    }

    private static Set<String> parseHosts(String csv) {
        Set<String> out = new LinkedHashSet<>();
        if (csv == null) return out;
        Arrays.stream(csv.split(",")).map(h -> h.trim().toLowerCase(Locale.ROOT)).filter(h -> !h.isEmpty())
                .forEach(out::add);
        return out;
    }

    /** Thrown when the body is empty or over {@code maxBytes}. */
    public static class TooLargeException extends RuntimeException {
        public TooLargeException(String message) { super(message); }
    }

    /** Thrown before any connection when the URL is not HTTPS on an allowed host. */
    public static class HostNotAllowedException extends RuntimeException {
        public HostNotAllowedException(String message) { super(message); }
    }

    /** True when {@code url} is HTTPS on an allowed host (plain HTTP only on an allowed loopback host). */
    public boolean allowed(URI url) {
        if (url == null || url.getHost() == null || url.getScheme() == null) return false;
        String host = url.getHost().toLowerCase(Locale.ROOT);
        String scheme = url.getScheme().toLowerCase(Locale.ROOT);
        boolean match = allowedHosts.stream().anyMatch(a -> host.equals(a) || host.endsWith("." + a));
        if (!match) return false;
        if (scheme.equals("https")) return true;
        boolean loopback = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1");
        return scheme.equals("http") && loopback && allowedHosts.contains(host);
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
        if (!allowed(url)) {
            log.warn("inbound media not fetched: {} is not HTTPS on an allowed host ({})",
                    url == null ? "null" : url.getScheme() + "://" + url.getHost(), allowedHosts);
            throw new HostNotAllowedException("media host not allowed: " + (url == null ? "null" : url.getHost()));
        }
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
