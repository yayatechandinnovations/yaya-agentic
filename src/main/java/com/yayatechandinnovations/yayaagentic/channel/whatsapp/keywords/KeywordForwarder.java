package com.yayatechandinnovations.yayaagentic.channel.whatsapp.keywords;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.auth.service_token.ServiceTokenAuthenticator;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.tool.dispatch.HttpEgressPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Hands a matched keyword inbound to the tenant and brings back its reply
 * (smart-lottery tracker C6/C8 P5). The POST is signed with the tenant's
 * service-token secret — the same {@code X-Yaya-Service-Token} the install
 * already verifies on outreach callbacks — so the tenant knows the platform
 * is calling about its own sender. 3 s per attempt, one retry when the
 * first never reached the tenant (connect failure / connect timeout) or it
 * answered 5xx; a request that timed out after connecting is NOT retried —
 * the tenant may already be acting on it and the reply would arrive twice.
 * Anything else is "no reply", logged, never thrown: the worker still files
 * the inbound.
 *
 * <p><b>Residual SSRF risk (DNS rebinding).</b> {@link HttpEgressPolicy}
 * resolves the forward host once and refuses private, loopback, link-local,
 * CGNAT and unique-local answers; the JDK {@link HttpClient} then resolves
 * the name again to connect. A host whose record flips between the two
 * lookups is not caught. Pinning the connection to the checked address is
 * not practical with this client (a literal-IP request loses the SNI and
 * cannot carry the original {@code Host} header, so TLS validation fails),
 * so the mitigations are: the URL is set by the tenant's admin, never by
 * the model; it must be {@code https} ({@link KeywordActionService}), so a
 * rebound private target would have to present a certificate valid for the
 * tenant's host name or the handshake fails before any byte of the inbound
 * or the token leaves the platform; redirects are never followed; and the
 * body carries only the inbound the tenant itself received.
 */
@Component
public class KeywordForwarder {

    private static final Logger log = LoggerFactory.getLogger(KeywordForwarder.class);

    public static final String TOKEN_HEADER = ServiceTokenAuthenticator.HEADER;
    public static final String SUBJECT_PREFIX = "keyword:";
    public static final String SCOPE = "keyword:forward";
    public static final Duration TIMEOUT = Duration.ofSeconds(3);
    static final Duration TOKEN_TTL = Duration.ofSeconds(60);
    private static final int MAX_REPLY_CHARS = 1500;

    private final ServiceTokenAuthenticator tokens;
    private final ObjectMapper json;
    private final HttpClient http;
    private final HttpEgressPolicy egress;
    private final Duration timeout;

    @org.springframework.beans.factory.annotation.Autowired
    public KeywordForwarder(ServiceTokenAuthenticator tokens, ObjectMapper json, HttpEgressPolicy egress) {
        this(tokens, json, HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(), egress, TIMEOUT);
    }

    /** @param egress null = no SSRF check (unit tests only) */
    KeywordForwarder(ServiceTokenAuthenticator tokens, ObjectMapper json, HttpClient http, HttpEgressPolicy egress) {
        this(tokens, json, http, egress, TIMEOUT);
    }

    KeywordForwarder(ServiceTokenAuthenticator tokens, ObjectMapper json, HttpClient http, HttpEgressPolicy egress,
                     Duration timeout) {
        this.tokens = tokens;
        this.json = json;
        this.http = http;
        this.egress = egress;
        this.timeout = timeout;
    }

    /** What the tenant receives: the inbound as it arrived, plus who it was for. */
    public record Inbound(Ids.TenantId tenant, SenderId sender, String from, String to, String text,
                          Instant receivedAt, String messageSid) {}

    /** The tenant's {@code reply}, when it answered with one. */
    public Optional<String> forward(KeywordAction action, Inbound inbound) {
        URI target = URI.create(action.forwardUrl());
        // The URL is admin-configured, never model-influenced, so only the SSRF
        // half of the egress policy applies — as for the delegate login URL.
        if (egress != null && egress.checkPrivateAddressOnly(target) instanceof HttpEgressPolicy.Decision.Deny deny) {
            log.warn("keyword forward to {} refused: {}", action.forwardUrl(), deny.reason());
            return Optional.empty();
        }
        Optional<String> token = tokens.mint(inbound.tenant(), SUBJECT_PREFIX + inbound.sender().value(),
                Set.of(SCOPE), TOKEN_TTL);
        if (token.isEmpty()) {
            log.warn("keyword «{}» for tenant {} not forwarded: no service-token secret to sign with",
                    action.prefix(), inbound.tenant().value());
            return Optional.empty();
        }
        byte[] body;
        try {
            body = json.writeValueAsBytes(payload(inbound));
        } catch (IOException e) {
            log.warn("keyword forward payload could not be encoded: {}", e.getMessage());
            return Optional.empty();
        }
        HttpRequest request = HttpRequest.newBuilder(target)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header(TOKEN_HEADER, token.get())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status >= 500) {
                    log.warn("keyword forward to {} answered {} (attempt {})", action.forwardUrl(), status, attempt);
                    continue;
                }
                if (status >= 400) {
                    log.warn("keyword forward to {} rejected with {}", action.forwardUrl(), status);
                    return Optional.empty();
                }
                return replyOf(response.body());
            } catch (HttpConnectTimeoutException e) {
                // Nothing reached the tenant: safe to try once more.
                log.warn("keyword forward to {} did not connect (attempt {}): {}", action.forwardUrl(), attempt,
                        e.getMessage());
            } catch (HttpTimeoutException e) {
                // The request went out and the answer never came: the tenant may
                // be acting on it already, so a second copy is worse than silence.
                log.warn("keyword forward to {} timed out after {}; not retried", action.forwardUrl(), timeout);
                return Optional.empty();
            } catch (IOException e) {
                log.warn("keyword forward to {} failed (attempt {}): {}", action.forwardUrl(), attempt,
                        e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    static Map<String, Object> payload(Inbound in) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenantId", in.tenant().value());
        m.put("senderId", in.sender().value());
        m.put("from", in.from());
        m.put("to", in.to());
        m.put("text", in.text() == null ? "" : in.text().trim());
        m.put("receivedAt", (in.receivedAt() == null ? Instant.now() : in.receivedAt()).toString());
        m.put("messageSid", in.messageSid());
        return m;
    }

    private Optional<String> replyOf(String body) {
        if (body == null || body.isBlank()) return Optional.empty();
        try {
            JsonNode node = json.readTree(body);
            JsonNode reply = node.path("reply");
            if (reply.isMissingNode() || reply.isNull()) return Optional.empty();
            String text = reply.asText("").trim();
            if (text.isEmpty()) return Optional.empty();
            return Optional.of(text.length() > MAX_REPLY_CHARS ? text.substring(0, MAX_REPLY_CHARS - 1) + "…" : text);
        } catch (IOException e) {
            log.warn("keyword forward reply was not JSON: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
