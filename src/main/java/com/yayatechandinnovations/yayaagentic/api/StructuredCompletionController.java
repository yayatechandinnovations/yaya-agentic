package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.StructuredCompletionDtos;
import com.yayatechandinnovations.yayaagentic.structured.StructuredCompletionAuthenticator;
import com.yayatechandinnovations.yayaagentic.structured.StructuredCompletionException;
import com.yayatechandinnovations.yayaagentic.structured.StructuredCompletionService;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * {@code POST /v1/structured-completions} — a stateless, single-shot "answer in
 * this JSON Schema" call for trusted tenant services (e.g. mapping free text
 * onto ids from a catalogue). Contract: {@code docs/guides/structured-completions.md}.
 *
 * <p>Admission happens before the body is read, and only through
 * {@link StructuredCompletionAuthenticator} (per-tenant HS256 service JWT with
 * scope {@code structured:complete}); the tenant is whatever the token proves.
 * Every outcome, errors included, is a JSON body of the documented shape.</p>
 */
@RestController
public class StructuredCompletionController {

    /** 200K system chars + 8K input chars at up to 4 bytes each, plus schema and JSON escaping. */
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

    private final StructuredCompletionAuthenticator authenticator;
    private final StructuredCompletionService service;

    public StructuredCompletionController(StructuredCompletionAuthenticator authenticator,
                                          StructuredCompletionService service) {
        this.authenticator = authenticator;
        this.service = service;
    }

    @PostMapping(value = "/v1/structured-completions", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Object>> complete(ServerWebExchange exchange) {
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        return Mono.fromCallable(() -> authenticator.authenticate(authorization))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(StructuredCompletionException.class, e -> service.recordRejected(null, e.code()))
                .flatMap(principal -> {
                    String tenant = principal.tenant().value();
                    return readBody(exchange)
                            .doOnError(StructuredCompletionException.class,
                                    e -> service.recordRejected(tenant, e.code()))
                            .flatMap(body -> service.complete(tenant, body));
                })
                .map(done -> ok(new StructuredCompletionDtos.Response(done.output(),
                        new StructuredCompletionDtos.Usage(done.inputTokens(), done.outputTokens()),
                        done.model())))
                .onErrorResume(StructuredCompletionException.class, e -> Mono.just(error(e)));
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
                        e -> StructuredCompletionException.invalidRequest("request body too large"))
                .defaultIfEmpty(new byte[0]);
    }

    private static ResponseEntity<Object> ok(StructuredCompletionDtos.Response body) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    private static ResponseEntity<Object> error(StructuredCompletionException e) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(e.status())
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (e.retryAfterSeconds() > 0) {
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()));
        }
        return response.body(new StructuredCompletionDtos.Error(e.code(), e.detail()));
    }
}
