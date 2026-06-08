package com.yayatechandinnovations.yayaagentic.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.api.dto.AdminDtos;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Reactive counterpart to {@link AdminExceptionHandler}. In WebFlux,
 * {@code ResponseStatusException} subclasses thrown from a {@code Mono}-
 * returning controller bypass {@code @RestControllerAdvice} handlers and hit
 * Spring's default error machinery, which writes the status but no body
 * (or a problem-details shape we don't control). This handler runs at the
 * framework level, recognises {@link AdminApiException}, and writes the
 * stable {@code {error, message}} shape clients expect.
 */
@Component
public class AdminApiWebExceptionHandler implements WebExceptionHandler, Ordered {

    private final ObjectMapper json;

    public AdminApiWebExceptionHandler(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public int getOrder() {
        // Beat Spring's DefaultErrorWebExceptionHandler (which is at -1).
        return -2;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (!(ex instanceof AdminApiException api)) {
            return Mono.error(ex);
        }
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) return Mono.error(ex);

        HttpStatus status = HttpStatus.valueOf(api.getStatusCode().value());
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String message = api.getReason() == null ? status.getReasonPhrase() : api.getReason();
        String body;
        try {
            body = json.writeValueAsString(new AdminDtos.ApiError(api.code(), message));
        } catch (Exception fallback) {
            body = "{\"error\":\"" + api.code() + "\",\"message\":\"" + status.getReasonPhrase() + "\"}";
        }
        DataBuffer buf = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buf));
    }
}
