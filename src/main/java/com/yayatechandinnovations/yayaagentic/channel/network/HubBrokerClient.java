package com.yayatechandinnovations.yayaagentic.channel.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Client for the Smart Lottery hub action plane (the network entry point's
 * broker) — yaya-agentic-network-chat-runtime-design.md §4. Every hub call is
 * authenticated with the runtime's own {@code X-Agent-Auth} credential and NEVER
 * the chat principal (§4/§6); every call carries {@code sessionRef} for the hub's
 * {@code agente_sesion_juego} audit ledger.
 *
 * <p>Two planes ride this one client:
 * <ul>
 *   <li><b>Hub calls</b> ({@code /internal/agent/**}) — {@code X-Agent-Auth}. Read
 *       tools, {@code play-session} brokering, personal reads.</li>
 *   <li><b>Install play-surface calls</b> — a {@link PlayCredential}'s
 *       {@code baseUrl} + {@code customerToken} bearer, the player-owned authority
 *       for draft/validate/execute/claim.</li>
 * </ul>
 *
 * <p>The phone is always supplied by the caller from the verified session (never
 * a model arg); this client never reads identity from tool input.
 */
@Component
public class HubBrokerClient {

    private static final Logger log = LoggerFactory.getLogger(HubBrokerClient.class);

    /** Two-reason broker errors (§4.1). The engine-facing copy is mapped per
     *  error by the calling tool; the audit line stays server-side. */
    public enum BrokerError {
        NOT_ENROLLED, NOT_LINKED, PLAY_DISABLED, TOKEN_EXPIRED, RATE_LIMITED, UNAVAILABLE, BAD_REQUEST
    }

    /** A hub/install response: {@code ok} with a body, or a typed error. */
    public record BrokerResponse(boolean ok, Map<String, Object> body, BrokerError error) {
        public static BrokerResponse ok(Map<String, Object> body) {
            return new BrokerResponse(true, body == null ? Map.of() : body, null);
        }

        public static BrokerResponse error(BrokerError e) {
            return new BrokerResponse(false, Map.of(), e);
        }
    }

    private final WebClient.Builder webClientBuilder;
    private final YayaAgenticProperties props;
    private final ObjectMapper json;
    private final NetworkRateLimiter rateLimiter;

    public HubBrokerClient(WebClient.Builder webClientBuilder,
                           YayaAgenticProperties props,
                           ObjectMapper json,
                           NetworkRateLimiter rateLimiter) {
        this.webClientBuilder = webClientBuilder;
        this.props = props;
        this.json = json;
        this.rateLimiter = rateLimiter;
    }

    // ---- hub action-plane calls (X-Agent-Auth) -------------------------

    public BrokerResponse linkedBancas(String phoneE164, String sessionRef) {
        if (!rateLimiter.allowBrokerCall(phoneE164)) return BrokerResponse.error(BrokerError.RATE_LIMITED);
        return hubGet("/internal/agent/players/" + enc(phoneE164) + "/bancas", Map.of(), sessionRef);
    }

    public BrokerResponse onlineBancas(Boolean openNow, String sessionRef) {
        // Directory is not per-player, so no per-phone budget applies here.
        Map<String, String> q = openNow == null ? Map.of() : Map.of("openNow", String.valueOf(openNow));
        return hubGet("/internal/agent/directory/online-bancas", q, sessionRef);
    }

    public BrokerResponse playerOverview(String phoneE164, String sessionRef) {
        if (!rateLimiter.allowBrokerCall(phoneE164)) return BrokerResponse.error(BrokerError.RATE_LIMITED);
        return hubGet("/internal/agent/players/" + enc(phoneE164) + "/overview", Map.of(), sessionRef);
    }

    public BrokerResponse playerPrizes(String phoneE164, String sessionRef) {
        if (!rateLimiter.allowBrokerCall(phoneE164)) return BrokerResponse.error(BrokerError.RATE_LIMITED);
        return hubGet("/internal/agent/players/" + enc(phoneE164) + "/prizes", Map.of(), sessionRef);
    }

    public BrokerResponse playerTickets(String phoneE164, Integer limit, String sessionRef) {
        if (!rateLimiter.allowBrokerCall(phoneE164)) return BrokerResponse.error(BrokerError.RATE_LIMITED);
        Map<String, String> q = limit == null ? Map.of() : Map.of("limit", String.valueOf(limit));
        return hubGet("/internal/agent/players/" + enc(phoneE164) + "/tickets", q, sessionRef);
    }

    /**
     * Broker a play session for {@code (installCode, phone)} — hub op {@code op}
     * (e.g. {@code "link"} for {@code banca_link_confirm}, {@code "play"} to vend a
     * {@link PlayCredential}). The credential (when present) is parsed by
     * {@link #parseCredential}.
     */
    public BrokerResponse playSession(String op, String installCode, String phoneE164, String sessionRef) {
        if (!rateLimiter.allowBrokerCall(phoneE164)) return BrokerResponse.error(BrokerError.RATE_LIMITED);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("op", op);
        body.put("installCode", installCode);
        body.put("phone", phoneE164);
        return hubPost("/internal/agent/play-session", body, sessionRef);
    }

    // ---- install play-surface calls (brokered customer token) ----------

    /**
     * Call an install's own play surface with the brokered {@link PlayCredential}
     * (its {@code baseUrl} + {@code customerToken} bearer). Used by
     * {@code play_quote}/{@code play_confirm}/{@code prize_claim_network}. The
     * token is never logged (§6).
     */
    public BrokerResponse playSurface(PlayCredential cred, HttpMethod method,
                                      String path, Map<String, Object> body, String sessionRef) {
        try {
            WebClient client = webClientBuilder.baseUrl(cred.baseUrl()).build();
            WebClient.RequestBodySpec req = client.method(method)
                    .uri(uriBuilder -> uriBuilder.path(path).build())
                    .header("Authorization", "Bearer " + cred.customerToken())
                    .header("X-Yaya-Session-Ref", nullSafe(sessionRef));
            @SuppressWarnings("unchecked")
            Map<String, Object> respBody = (method == HttpMethod.GET || body == null
                    ? req.retrieve()
                    : req.contentType(MediaType.APPLICATION_JSON).bodyValue(body).retrieve())
                    .bodyToMono(Map.class).block(timeout());
            return BrokerResponse.ok(respBody);
        } catch (WebClientResponseException e) {
            return mapError(e);
        } catch (RuntimeException e) {
            log.warn("[network] install play-surface call failed path={} sessionRef={} err={}",
                    path, sessionRef, e.getClass().getSimpleName());
            return BrokerResponse.error(BrokerError.UNAVAILABLE);
        }
    }

    /** Parse a {@link PlayCredential} out of a {@code play-session} response body,
     *  if one is present. */
    @SuppressWarnings("unchecked")
    public Optional<PlayCredential> parseCredential(Map<String, Object> body) {
        if (body == null) return Optional.empty();
        Object raw = body.get("playCredential");
        if (!(raw instanceof Map<?, ?> m)) return Optional.empty();
        Map<String, Object> pc = (Map<String, Object>) m;
        String token = str(pc.get("customerToken"));
        if (token == null || token.isBlank()) return Optional.empty();
        java.time.Instant exp = null;
        try {
            Object e = pc.get("expiresAt");
            if (e != null) exp = java.time.Instant.parse(String.valueOf(e));
        } catch (RuntimeException ignore) {
            // Missing/garbled expiry → treat as already expired so the next use
            // re-brokers rather than trusting an unknown lifetime.
        }
        return Optional.of(new PlayCredential(
                str(pc.get("baseUrl")), str(pc.get("customerId")), token, exp));
    }

    // ---- transport -----------------------------------------------------

    private BrokerResponse hubGet(String path, Map<String, String> query, String sessionRef) {
        return hubCall(HttpMethod.GET, path, query, null, sessionRef);
    }

    private BrokerResponse hubPost(String path, Map<String, Object> body, String sessionRef) {
        return hubCall(HttpMethod.POST, path, Map.of(), body, sessionRef);
    }

    private BrokerResponse hubCall(HttpMethod method, String path, Map<String, String> query,
                                   Map<String, Object> body, String sessionRef) {
        String base = props.network().hub() == null ? null : props.network().hub().baseUrl();
        if (base == null || base.isBlank()) {
            log.warn("[network] hub base-url not configured; refusing broker call path={}", path);
            return BrokerResponse.error(BrokerError.UNAVAILABLE);
        }
        try {
            WebClient client = webClientBuilder.baseUrl(base).build();
            WebClient.RequestBodySpec req = client.method(method)
                    .uri(uriBuilder -> {
                        uriBuilder.path(path);
                        query.forEach(uriBuilder::queryParam);
                        return uriBuilder.build();
                    })
                    .header("X-Agent-Auth", agentCredential())
                    .header("X-Yaya-Session-Ref", nullSafe(sessionRef));
            @SuppressWarnings("unchecked")
            Map<String, Object> respBody = (method == HttpMethod.GET || body == null
                    ? req.retrieve()
                    : req.contentType(MediaType.APPLICATION_JSON).bodyValue(body).retrieve())
                    .bodyToMono(Map.class).block(timeout());
            return BrokerResponse.ok(respBody);
        } catch (WebClientResponseException e) {
            return mapError(e);
        } catch (RuntimeException e) {
            log.warn("[network] hub call failed path={} sessionRef={} err={}",
                    path, sessionRef, e.getClass().getSimpleName());
            return BrokerResponse.error(BrokerError.UNAVAILABLE);
        }
    }

    /** Map an HTTP error to a typed {@link BrokerError}. A body {@code error} code
     *  matching an enum name wins; else the status class decides. */
    private BrokerResponse mapError(WebClientResponseException e) {
        String code = errorCode(e.getResponseBodyAsString());
        if (code != null) {
            try {
                return BrokerResponse.error(BrokerError.valueOf(code));
            } catch (IllegalArgumentException ignore) {
                // Unknown code — fall through to status mapping.
            }
        }
        int s = e.getStatusCode().value();
        BrokerError mapped = switch (s) {
            case 401, 403 -> BrokerError.TOKEN_EXPIRED;
            case 429 -> BrokerError.RATE_LIMITED;
            default -> s >= 500 ? BrokerError.UNAVAILABLE : BrokerError.BAD_REQUEST;
        };
        return BrokerResponse.error(mapped);
    }

    private String errorCode(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) return null;
        try {
            Map<?, ?> m = json.readValue(responseBody, Map.class);
            Object code = m.get("error");
            return code == null ? null : String.valueOf(code);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            return null;
        }
    }

    /**
     * Resolve the {@code X-Agent-Auth} credential. {@code credentialRef} is a
     * REFERENCE, not the secret: if an env var by that name is set, its value is
     * used; otherwise the ref itself is used verbatim (local/dev). The credential
     * is never logged.
     */
    private String agentCredential() {
        String ref = props.network().hub() == null ? null : props.network().hub().credentialRef();
        if (ref == null || ref.isBlank()) return "";
        String fromEnv = System.getenv(ref);
        return fromEnv != null && !fromEnv.isBlank() ? fromEnv : ref;
    }

    private Duration timeout() {
        return props.network().hub() == null ? Duration.ofSeconds(10) : props.network().hub().timeout();
    }

    private static String enc(String s) {
        return UriUtils.encodePathSegment(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
