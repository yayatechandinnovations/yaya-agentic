package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatRateLimiter;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The upload endpoint admits (owner token) and rate-limits BEFORE it reads the
 * multipart body, and refuses a declared size over the read bound outright:
 * an anonymous caller cannot make the server buffer 16 MB.
 */
class SessionAttachmentControllerTest {

    private final SessionController sessions = mock(SessionController.class);
    private final ChatRateLimiter rateLimiter = mock(ChatRateLimiter.class);
    private final SessionAttachments attachments = mock(SessionAttachments.class);
    private final SessionAttachmentController controller =
            new SessionAttachmentController(sessions, rateLimiter, attachments);

    /** A request whose body publisher records whether anything ever subscribed to it. */
    private record Request(MockServerWebExchange exchange, AtomicBoolean bodyRead) {}

    private static Request request(long declaredLength) {
        AtomicBoolean read = new AtomicBoolean();
        DefaultDataBufferFactory factory = new DefaultDataBufferFactory();
        Flux<DataBuffer> body = Flux.defer(() -> {
            read.set(true);
            return Flux.just(factory.wrap("--b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"x.png\"\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII)));
        });
        MockServerHttpRequest req = MockServerHttpRequest.post("/v1/sessions/s-1/attachments")
                .contentType(MediaType.parseMediaType("multipart/form-data; boundary=b"))
                .header(HttpHeaders.CONTENT_LENGTH, Long.toString(declaredLength))
                .body(body);
        return new Request(MockServerWebExchange.from(req), read);
    }

    @Test
    void an_unauthenticated_caller_is_refused_before_the_body_is_read() {
        when(sessions.admit(eq("s-1"), any(), eq(true))).thenThrow(ChatApiException.authRequired());
        Request r = request(15L * 1024 * 1024);

        assertThatThrownBy(() -> controller.upload("s-1", r.exchange()).block(Duration.ofSeconds(5)))
                .isInstanceOf(ChatApiException.class)
                .satisfies(e -> assertThat(((ChatApiException) e).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));

        assertThat(r.bodyRead()).isFalse();
        verify(attachments, never()).service();
        verify(rateLimiter, never()).hit(anyString(), anyString(), anyInt(), any());
    }

    @Test
    void a_session_nobody_owns_is_refused_before_the_body_is_read() {
        SessionController.Caller anonymous = new SessionController.Caller(
                new AuthContext(new Ids.TenantId("t"), Map.of(), Optional.empty()), null, null, null);
        when(sessions.admit(eq("s-1"), any(), eq(true))).thenReturn(anonymous);
        Request r = request(15L * 1024 * 1024);

        assertThatThrownBy(() -> controller.upload("s-1", r.exchange()).block(Duration.ofSeconds(5)))
                .isInstanceOf(ChatApiException.class)
                .satisfies(e -> {
                    assertThat(((ChatApiException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(((ChatApiException) e).code()).isEqualTo("owner_required");
                });

        assertThat(r.bodyRead()).isFalse();
        verify(attachments, never()).service();
    }

    @Test
    void a_declared_size_over_the_read_bound_is_413_before_admission_or_the_body() {
        Request r = request(20L * 1024 * 1024);

        ResponseEntity<Object> out = controller.upload("s-1", r.exchange()).block(Duration.ofSeconds(5));

        assertThat(out).isNotNull();
        assertThat(out.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(((Map<?, ?>) out.getBody()).get("code")).isEqualTo("too_large");
        assertThat(r.bodyRead()).isFalse();
        verify(sessions, never()).admit(anyString(), any(), eq(true));
        verify(attachments, never()).service();
    }

    @Test
    void a_rate_limited_owner_is_refused_before_the_body_is_read() {
        SessionController.Caller owner = new SessionController.Caller(
                new AuthContext(new Ids.TenantId("t"), Map.of(), Optional.empty()), null,
                new com.yayatechandinnovations.yayaagentic.core.Principal("u-1", new Ids.TenantId("t"),
                        java.util.Set.of(), Map.of(), java.time.Instant.now()), null);
        when(sessions.admit(eq("s-1"), any(), eq(true))).thenReturn(owner);
        when(rateLimiter.hit(eq("sess-att"), eq("s-1"), anyInt(), any()))
                .thenReturn(new ChatRateLimiter.Decision(false, 30));
        Request r = request(1024);

        ResponseEntity<Object> out = controller.upload("s-1", r.exchange()).block(Duration.ofSeconds(5));

        assertThat(out).isNotNull();
        assertThat(out.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(out.getHeaders().getFirst("Retry-After")).isEqualTo("30");
        assertThat(r.bodyRead()).isFalse();
        verify(attachments, never()).service();
    }
}
