package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.PublicChatDtos;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The public chat surface documents a stable machine-readable {@code code}
 * (chat-channel-design.md §7), but ChatApiException extends
 * ResponseStatusException and was being serialized by the generic handler,
 * which drops it. Clients branch on that code — a codeless
 * {@code verification_required} reads as a plain connection failure.
 */
class AdminExceptionHandlerChatErrorTest {

    private final AdminExceptionHandler handler = new AdminExceptionHandler();

    @Test
    void verificationRequiredKeepsItsCodeAndStatus() {
        ResponseEntity<PublicChatDtos.ChatError> response =
                handler.handle(ChatApiException.verificationRequired());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("verification_required");
        // Unchanged for existing consumers.
        assertThat(response.getBody().error()).isEqualTo("FORBIDDEN");
        assertThat(response.getBody().message())
                .isEqualTo("verify your phone number to start this chat");
    }

    @Test
    void otherChatCodesSurviveToo() {
        assertThat(handler.handle(ChatApiException.authRequired()).getBody().code())
                .isEqualTo("auth_required");
        assertThat(handler.handle(ChatApiException.unknownHandle()).getBody().code())
                .isEqualTo("not_found");
        assertThat(handler.handle(ChatApiException.sessionNotFound()).getBody().code())
                .isEqualTo("session_not_found");
        // A 403 that must NOT be read as "go verify" — the client keys on the
        // code precisely so this one doesn't loop the user through OTP.
        assertThat(handler.handle(ChatApiException.resolverUnresolved()).getBody().code())
                .isEqualTo("resolver_unresolved");
    }

    @Test
    void rateLimitedCarriesRetryAfter() {
        ResponseEntity<PublicChatDtos.ChatError> response =
                handler.handle(ChatApiException.rateLimited(42));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody().code()).isEqualTo("rate_limited");
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("42");
    }

    @Test
    void nonChatResponseStatusExceptionsKeepTheLegacyShape() {
        var response = handler.handle(
                new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.BAD_GATEWAY, "upstream said no"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody().error()).isEqualTo("BAD_GATEWAY");
        assertThat(response.getBody().message()).isEqualTo("upstream said no");
    }
}
