package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.SessionDtos;
import com.yayatechandinnovations.yayaagentic.auth.playground.ActAs;
import com.yayatechandinnovations.yayaagentic.auth.playground.PlaygroundActAsRegistry;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * End-to-end wiring for the playground "act-as" credential channel — design
 * {@code docs/design/playground-actas-auth-design.md} §5. Proves that the
 * controller deserializes the polymorphic {@code actAs} field, populates the
 * {@link PlaygroundActAsRegistry}, and rejects malformed specs with 422
 * without ever invoking the engine.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
class SessionControllerActAsTest {

    @Autowired WebTestClient client;
    @Autowired PlaygroundActAsRegistry registry;

    @Test
    void startSession_withRawTokenActAs_storesSpec() {
        SessionDtos.StartSessionResponse start = client.post().uri("/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "tenant", "default",
                        "profileId", "hello-world",
                        "profileVersion", 1,
                        "channel", "playground",
                        "hints", Map.of(),
                        "actAs", Map.of(
                                "kind", "raw-token",
                                "scheme", "Bearer",
                                "token", "eyJhbGc.payload.sig")))
                .exchange()
                .expectStatus().isOk()
                .expectBody(SessionDtos.StartSessionResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(start).isNotNull();
        assertThat(registry.get(new Ids.SessionId(start.sessionId())))
                .containsInstanceOf(ActAs.RawToken.class)
                .hasValueSatisfying(spec -> {
                    ActAs.RawToken raw = (ActAs.RawToken) spec;
                    assertThat(raw.scheme()).isEqualTo("Bearer");
                    assertThat(raw.token()).isEqualTo("eyJhbGc.payload.sig");
                });
    }

    @Test
    void startSession_withoutActAs_storesNothing() {
        SessionDtos.StartSessionResponse start = client.post().uri("/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SessionDtos.StartSessionRequest(
                        "default", "hello-world", 1, "playground", Map.of(), null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(SessionDtos.StartSessionResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(start).isNotNull();
        assertThat(registry.get(new Ids.SessionId(start.sessionId()))).isEmpty();
    }

    @Test
    void startSession_blankRawTokenActAs_returns422() {
        client.post().uri("/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "tenant", "default",
                        "profileId", "hello-world",
                        "profileVersion", 1,
                        "channel", "playground",
                        "hints", Map.of(),
                        "actAs", Map.of("kind", "raw-token", "scheme", "Bearer", "token", "")))
                .exchange()
                .expectStatus().isEqualTo(422);
    }

    @Test
    void startSession_disallowedSchemeActAs_returns422() {
        client.post().uri("/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "tenant", "default",
                        "profileId", "hello-world",
                        "profileVersion", 1,
                        "channel", "playground",
                        "hints", Map.of(),
                        "actAs", Map.of("kind", "raw-token", "scheme", "Negotiate", "token", "tok")))
                .exchange()
                .expectStatus().isEqualTo(422);
    }

    @Test
    void endSession_clearsActAsRegistry() {
        SessionDtos.StartSessionResponse start = client.post().uri("/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "tenant", "default",
                        "profileId", "hello-world",
                        "profileVersion", 1,
                        "channel", "playground",
                        "hints", Map.of(),
                        "actAs", Map.of("kind", "raw-token", "scheme", "Bearer", "token", "tok")))
                .exchange()
                .expectStatus().isOk()
                .expectBody(SessionDtos.StartSessionResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(start).isNotNull();
        Ids.SessionId sid = new Ids.SessionId(start.sessionId());
        assertThat(registry.get(sid)).isPresent();

        client.post().uri("/v1/sessions/{id}/end", start.sessionId())
                .exchange()
                .expectStatus().isOk();

        assertThat(registry.get(sid)).isEmpty();
    }
}
