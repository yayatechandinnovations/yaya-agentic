package com.yayatechandinnovations.yayaagentic.channel.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.yayatechandinnovations.yayaagentic.tool.dispatch.HttpEgressPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/**
 * Per-row {@link AuthResolver} that POSTs to
 * {@code tenant.host_base_url + http_path} and extracts a JWT from the
 * response body. Not a Spring bean — instantiated by
 * {@link HttpAuthResolverFactory} from a {@code channel_auth_resolvers}
 * row + the resolved tenant {@code host_base_url}.
 *
 * <p>See {@code docs/design/channel-auth-resolver-design.md} §5.2, §6.
 */
public final class HttpAuthResolver implements AuthResolver {

    private static final Logger log = LoggerFactory.getLogger(HttpAuthResolver.class);

    private static final String YAYA_ISSUER = "yaya-agentic";
    private static final Duration SIGNED_JWT_LIFETIME = Duration.ofSeconds(30);

    private final String name;
    private final String description;
    private final URI url;
    private final ServiceAuthKind serviceAuthKind;
    private final String serviceSecret;          // plaintext after JwtSecretCipher.decrypt
    private final Duration timeout;
    private final WebClient http;
    private final HttpEgressPolicy egressPolicy;
    private final ObjectMapper json;
    private final Clock clock;
    private final ResolverExchangeStore exchangeStore;

    HttpAuthResolver(String name,
                     String description,
                     URI url,
                     ServiceAuthKind serviceAuthKind,
                     String serviceSecret,
                     Duration timeout,
                     WebClient http,
                     HttpEgressPolicy egressPolicy,
                     ObjectMapper json,
                     Clock clock,
                     ResolverExchangeStore exchangeStore) {
        this.name = name;
        this.description = description;
        this.url = url;
        this.serviceAuthKind = serviceAuthKind;
        this.serviceSecret = serviceSecret;
        this.timeout = timeout;
        this.http = http;
        this.egressPolicy = egressPolicy;
        this.json = json;
        this.clock = clock;
        this.exchangeStore = exchangeStore;
    }

    public enum ServiceAuthKind { NONE, BEARER, SIGNED_JWT }

    @Override public String name() { return name; }
    @Override public String description() { return description; }

    /** Exposed for the admin test-probe and audit row composition. */
    public URI url() { return url; }

    @Override
    public Optional<String> resolve(ResolveRequest req) throws AuthResolverException {
        HttpEgressPolicy.Decision decision = egressPolicy.check(url);
        if (decision instanceof HttpEgressPolicy.Decision.Deny d) {
            throw new AuthResolverException("allowlist_denied",
                    "egress denied for resolver '" + name + "': " + d.reason());
        }

        String requestId = "rq_" + UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", req.session() == null ? null : req.session().value());
        body.put("senderPhone", req.e164SenderPhone());
        body.put("callerPhone", req.e164CallerPhone());
        body.put("channel", req.channel());
        body.put("tenantId", req.tenant().value());
        body.put("reason", req.reason() == ResolveReason.REFRESH ? "refresh" : "initial");
        body.put("timestamp", req.requestedAt() == null
                ? Instant.now(clock).toString()
                : req.requestedAt().toString());

        WebClient.RequestBodySpec spec = http.post()
                .uri(url)
                .header("X-Yaya-Tenant", req.tenant().value())
                .header("X-Yaya-Request-Id", requestId)
                .header("X-Yaya-Idempotency-Key", requestId)
                .contentType(MediaType.APPLICATION_JSON);

        String authHeader = buildAuthHeader(req, requestId);
        if (authHeader != null) {
            spec = (WebClient.RequestBodySpec) spec.header("Authorization", authHeader);
        }

        // Redacted request snapshot for the playground auth-flow inspector. The
        // outbound Authorization is yaya's own service credential — never the
        // debugging target, always redacted (design §13.1).
        Map<String, String> capturedHeaders = new LinkedHashMap<>();
        capturedHeaders.put("X-Yaya-Tenant", req.tenant().value());
        capturedHeaders.put("X-Yaya-Request-Id", requestId);
        capturedHeaders.put("Content-Type", MediaType.APPLICATION_JSON_VALUE);
        if (authHeader != null) {
            capturedHeaders.put("Authorization",
                    "<" + serviceAuthKind + " service credential, redacted>");
        }
        java.util.concurrent.atomic.AtomicInteger statusHolder =
                new java.util.concurrent.atomic.AtomicInteger(-1);
        java.util.concurrent.atomic.AtomicReference<String> bodyHolder =
                new java.util.concurrent.atomic.AtomicReference<>("");

        String rawBody;
        try {
            rawBody = spec.bodyValue(body)
                    .exchangeToMono(resp -> {
                        int status = resp.statusCode().value();
                        statusHolder.set(status);
                        if (status == HttpStatus.NOT_FOUND.value()) {
                            return resp.bodyToMono(String.class).defaultIfEmpty("")
                                    .doOnNext(bodyHolder::set)
                                    .then(reactor.core.publisher.Mono.empty());
                        }
                        if (status == 401 || status == 403) {
                            return resp.bodyToMono(String.class)
                                    .defaultIfEmpty("")
                                    .doOnNext(bodyHolder::set)
                                    .flatMap(b -> reactor.core.publisher.Mono.error(
                                            new AuthResolverException("self_auth_failed",
                                                    "resolver '" + name + "' rejected our outbound auth ("
                                                            + status + ")", status, null)));
                        }
                        if (status >= 400) {
                            return resp.bodyToMono(String.class)
                                    .defaultIfEmpty("")
                                    .doOnNext(bodyHolder::set)
                                    .flatMap(b -> reactor.core.publisher.Mono.error(
                                            new AuthResolverException("transport_error",
                                                    "resolver '" + name + "' returned " + status,
                                                    status, null)));
                        }
                        return resp.bodyToMono(String.class).defaultIfEmpty("")
                                .doOnNext(bodyHolder::set);
                    })
                    .block(timeout);
        } catch (AuthResolverException are) {
            recordExchange(req, requestId, capturedHeaders, body,
                    statusHolder.get(), bodyHolder.get(), are.status() + ": " + are.getMessage());
            throw are;
        } catch (RuntimeException rte) {
            AuthResolverException toThrow;
            if (rte.getCause() instanceof TimeoutException
                    || rte instanceof IllegalStateException
                            && rte.getMessage() != null
                            && rte.getMessage().contains("Timeout")) {
                toThrow = new AuthResolverException("timeout",
                        "resolver '" + name + "' timed out after " + timeout.toMillis() + "ms",
                        rte);
            } else {
                toThrow = new AuthResolverException("transport_error",
                        "resolver '" + name + "' transport error: " + rte.getClass().getSimpleName(),
                        rte);
            }
            recordExchange(req, requestId, capturedHeaders, body,
                    statusHolder.get(), bodyHolder.get(), toThrow.status() + ": " + toThrow.getMessage());
            throw toThrow;
        }

        recordExchange(req, requestId, capturedHeaders, body,
                statusHolder.get(), bodyHolder.get(), null);

        if (rawBody == null || rawBody.isBlank()) {
            // 404 short-circuited to empty body OR 200 with empty body — both
            // map to "user not found" per design §14.
            return Optional.empty();
        }
        Map<String, Object> response;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = json.readValue(rawBody, Map.class);
            response = parsed;
        } catch (Exception e) {
            throw new AuthResolverException("bad_token",
                    "resolver '" + name + "' returned a body we could not parse as JSON",
                    e);
        }
        Object token = response.get("token");
        if (token == null || !(token instanceof String s) || s.isBlank()) {
            return Optional.empty();
        }
        // Don't log s itself — non-negotiable §13.1: no JWT cleartext in logs.
        log.debug("auth resolver '{}' returned a token of {} bytes", name, s.length());
        return Optional.of(s);
    }

    /** Record the (already-redaction-safe) exchange for the playground
     *  inspector. Never affects the resolve path. */
    private void recordExchange(ResolveRequest req, String requestId,
                                Map<String, String> headers, Map<String, Object> body,
                                int status, String responseBody, String error) {
        if (exchangeStore == null) return;
        try {
            String callerHash = com.yayatechandinnovations.yayaagentic.channel.auth.cache
                    .AuthResolverTokenCache.sha256LowerHex(req.e164CallerPhone());
            exchangeStore.record(req.tenant().value(), callerHash,
                    new ResolverExchangeStore.Exchange(
                            requestId, "POST", url.toString(),
                            new LinkedHashMap<>(headers),
                            new LinkedHashMap<>(body),
                            status < 0 ? null : status,
                            redactTokenInBody(responseBody),
                            error,
                            Instant.now(clock).toString()));
        } catch (RuntimeException ignored) {
            // Debug capture is best-effort; never let it break resolution.
        }
    }

    /** Replace the JWT in a {@code {"token": "..."}} response with a length
     *  placeholder so the in-memory store never holds a usable bearer token. */
    private String redactTokenInBody(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) return rawBody;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = json.readValue(rawBody, Map.class);
            Object t = parsed.get("token");
            if (t instanceof String s && !s.isBlank()) {
                parsed.put("token", "<JWT, " + s.length() + " bytes, redacted>");
            }
            return json.writeValueAsString(parsed);
        } catch (Exception e) {
            // Not JSON we can rewrite — error bodies rarely carry a token.
            return rawBody;
        }
    }

    private String buildAuthHeader(ResolveRequest req, String requestId) {
        return switch (serviceAuthKind) {
            case NONE -> null;
            case BEARER -> serviceSecret == null ? null : "Bearer " + serviceSecret;
            case SIGNED_JWT -> "Bearer " + mintSignedJwt(req, requestId);
        };
    }

    private String mintSignedJwt(ResolveRequest req, String requestId) {
        if (serviceSecret == null) {
            throw new AuthResolverException("self_auth_failed",
                    "resolver '" + name + "' has SIGNED_JWT auth but no secret configured");
        }
        Instant now = Instant.now(clock);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(YAYA_ISSUER)
                .audience(req.tenant().value())
                .subject("auth-resolver:" + name)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(SIGNED_JWT_LIFETIME)))
                .jwtID(requestId)
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(new MACSigner(serviceSecret.getBytes()));
        } catch (JOSEException e) {
            throw new AuthResolverException("self_auth_failed",
                    "resolver '" + name + "' failed to sign outbound JWT: "
                            + e.getClass().getSimpleName(), e);
        }
        return jwt.serialize();
    }

    /** Compose an absolute URI from a tenant {@code hostBaseUrl} and a
     *  {@code httpPath}. The path must start with {@code /}; the base may or
     *  may not have a trailing slash. */
    public static URI composeUrl(String hostBaseUrl, String httpPath) {
        if (hostBaseUrl == null || hostBaseUrl.isBlank()) {
            throw new IllegalArgumentException("hostBaseUrl required");
        }
        if (httpPath == null || !httpPath.startsWith("/")) {
            throw new IllegalArgumentException("httpPath must start with '/'");
        }
        String base = hostBaseUrl.endsWith("/")
                ? hostBaseUrl.substring(0, hostBaseUrl.length() - 1)
                : hostBaseUrl;
        try {
            return new URI(base + httpPath);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(
                    "could not compose URL from base=" + base + " path=" + httpPath, e);
        }
    }
}
