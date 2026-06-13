package com.yayatechandinnovations.yayaagentic;

import com.yayatechandinnovations.yayaagentic.api.dto.SessionDtos;
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
 * M0 smoke test. Stub LLM is the default; no API keys needed.
 *
 * Exercises: POST /v1/sessions returns a greeting + quick-replies;
 * POST /v1/sessions/{id}/messages streams SSE events for both an LLM-driven
 * reply ("hello") and a tool-driven reply ("echo hi").
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
class YayaAgenticApplicationTests {

    @Autowired
    WebTestClient client;

    @Test
    void startSession_returnsGreetingAndQuickReplies() {
        SessionDtos.StartSessionResponse start = startHelloWorld();
        assertThat(start.sessionId()).isNotBlank();
        assertThat(start.profileId()).isEqualTo("hello-world");
        assertThat(start.profileVersion()).isEqualTo(1);
        assertThat(start.greeting()).contains("Yaya");
        assertThat(start.quickReplies()).contains("echo hello");
    }

    @Test
    void sendMessage_streamsTokensForPlainTurn() {
        String sessionId = startHelloWorld().sessionId();

        String body = sendAndCollect(sessionId, "hello");
        assertThat(body).contains("event:token");
        assertThat(body).contains("event:end");
    }

    @Test
    void sendMessage_streamsToolCallAndResultForEchoTurn() {
        String sessionId = startHelloWorld().sessionId();

        String body = sendAndCollect(sessionId, "echo hi");
        assertThat(body).contains("event:tool_call");
        assertThat(body).contains("event:tool_result");
        assertThat(body).contains("\"echo\":\"hi\"");
        assertThat(body).contains("event:end");
    }

    // ---- helpers ----------------------------------------------------

    private SessionDtos.StartSessionResponse startHelloWorld() {
        return client.post().uri("/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SessionDtos.StartSessionRequest("default", "hello-world", 1, "web", Map.of(), null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(SessionDtos.StartSessionResponse.class)
                .returnResult()
                .getResponseBody();
    }

    private String sendAndCollect(String sessionId, String text) {
        byte[] raw = client.post().uri("/v1/sessions/{id}/messages", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(new SessionDtos.MessageRequest(text))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .returnResult()
                .getResponseBody();
        return raw == null ? "" : new String(raw).replace(" ", "");
    }
}
