package com.yayatechandinnovations.yayaagentic.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.headless.HeadlessRunAuthenticator;
import com.yayatechandinnovations.yayaagentic.headless.HeadlessRunException;
import com.yayatechandinnovations.yayaagentic.headless.HeadlessRunService;
import com.yayatechandinnovations.yayaagentic.headless.HeadlessRunStore;
import com.yayatechandinnovations.yayaagentic.outreach.ReplayGuard;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code POST /v1/headless/runs} and {@code GET /v1/headless/runs/{runId}} —
 * a tenant service has the profile's agent answer a stored question with
 * nobody present, and polls for the answer. Contract:
 * {@code docs/guides/admin-panel-sessions.md} §17.
 *
 * <p>Admission happens before the body is read and only through
 * {@link HeadlessRunAuthenticator} (per-tenant HS256 service JWT with scope
 * {@code headless:run}), then {@link ReplayGuard} ({@code X-Nonce},
 * {@code X-Timestamp}). The tenant is whatever the token proves — never a
 * field of the request. Every outcome, errors included, is a JSON body.</p>
 */
@RestController
public class HeadlessRunController {

    /** A question, a token and a small context: far below this. */
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};

    private final HeadlessRunAuthenticator authenticator;
    private final ReplayGuard replayGuard;
    private final HeadlessRunService service;
    private final ObjectMapper json;

    public HeadlessRunController(HeadlessRunAuthenticator authenticator, ReplayGuard replayGuard,
                                 HeadlessRunService service, ObjectMapper json) {
        this.authenticator = authenticator;
        this.replayGuard = replayGuard;
        this.service = service;
        this.json = json;
    }

    @PostMapping(value = "/v1/headless/runs", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Object>> submit(ServerWebExchange exchange) {
        return admit(exchange)
                .flatMap(tenant -> readBody(exchange).flatMap(body -> Mono.fromCallable(() ->
                        service.submit(tenant, header(exchange, "X-Idem-Key"), body))
                        .subscribeOn(Schedulers.boundedElastic())))
                .map(accepted -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("runId", accepted.runId());
                    body.put("status", accepted.status());
                    return respond(HttpStatus.ACCEPTED, body);
                })
                .onErrorResume(HeadlessRunException.class, e -> Mono.just(error(e)));
    }

    @GetMapping(value = "/v1/headless/runs/{runId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Object>> get(@PathVariable("runId") String runId, ServerWebExchange exchange) {
        return admit(exchange)
                .flatMap(tenant -> Mono.fromCallable(() -> service.get(tenant, runId))
                        .subscribeOn(Schedulers.boundedElastic()))
                .map(run -> respond(HttpStatus.OK, view(run)))
                .onErrorResume(HeadlessRunException.class, e -> Mono.just(error(e)));
    }

    /** The tenant the service token proves, once the request is shown to be fresh. */
    private Mono<String> admit(ServerWebExchange exchange) {
        String authorization = header(exchange, HttpHeaders.AUTHORIZATION);
        String nonce = header(exchange, "X-Nonce");
        String timestamp = header(exchange, "X-Timestamp");
        return Mono.fromCallable(() -> {
            Principal service = authenticator.authenticate(authorization);
            String tenant = service.tenant().value();
            long epochMs;
            try {
                epochMs = Long.parseLong(timestamp == null ? "" : timestamp.trim());
            } catch (NumberFormatException e) {
                throw HeadlessRunException.replay("X-Timestamp (epoch millis) required");
            }
            ReplayGuard.Result fresh = replayGuard.check("headless:" + tenant, nonce, epochMs);
            if (fresh instanceof ReplayGuard.Result.Rejected rejected) {
                throw HeadlessRunException.replay(rejected.reason());
            }
            return tenant;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** The run as the caller reads it: no token, no session, no question. */
    private Map<String, Object> view(HeadlessRunStore.Run run) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", run.id());
        out.put("status", run.status());
        if (HeadlessRunStore.COMPLETED.equals(run.status()) && run.answer() != null) {
            out.put("answer", run.answer());
        }
        if (HeadlessRunStore.FAILED.equals(run.status())) {
            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put("code", run.failureCode());
            failure.put("message", run.failureMessage() == null ? "" : run.failureMessage());
            out.put("failure", failure);
        }
        out.put("usage", usage(run.usageJson()));
        out.put("tools", tools(run.toolsJson()));
        if (run.startedAt() != null) out.put("startedAt", run.startedAt().toString());
        if (run.finishedAt() != null) out.put("finishedAt", run.finishedAt().toString());
        return out;
    }

    private Map<String, Object> usage(String raw) {
        Map<String, Object> usage = new LinkedHashMap<>();
        for (String field : List.of("inputTokens", "outputTokens", "cacheReadTokens", "rounds", "toolCalls")) {
            usage.put(field, 0);
        }
        if (raw != null) {
            try {
                usage.putAll(json.readValue(raw, MAP));
            } catch (Exception ignored) {
                // an unreadable row reads as zeros
            }
        }
        return usage;
    }

    private List<String> tools(String raw) {
        if (raw == null) return List.of();
        try {
            return json.readValue(raw, STRINGS);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Mono<byte[]> readBody(ServerWebExchange exchange) {
        return DataBufferUtils.join(exchange.getRequest().getBody(), MAX_BODY_BYTES)
                .map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    return bytes;
                })
                .onErrorMap(DataBufferLimitException.class,
                        e -> HeadlessRunException.invalidRequest("request body too large"))
                .defaultIfEmpty(new byte[0]);
    }

    private static String header(ServerWebExchange exchange, String name) {
        return exchange.getRequest().getHeaders().getFirst(name);
    }

    private static ResponseEntity<Object> respond(HttpStatus status, Object body) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    private static ResponseEntity<Object> error(HeadlessRunException e) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(e.status())
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (e.retryAfterSeconds() > 0) {
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()));
        }
        return response.body(e.body());
    }
}
