package com.yayatechandinnovations.yayaagentic.headless;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code {session.channel}} of a headless run is {@code automation}
 * ({@code docs/guides/admin-panel-sessions.md} §14, §17), and the tool call
 * carries the run token as it was given.
 */
class HeadlessChannelVariableTest extends HeadlessRunTestBase {

    @Test
    void the_channel_a_tool_request_carries_is_automation_and_the_token_is_the_run_token() {
        String runToken = runToken("u-" + UUID.randomUUID());
        llm.tool("c1", "hl_me", Map.of());
        llm.say("Eres la dueña.");

        Map<String, Object> run = finished(accepted(body(runToken, "¿Quién soy?")));

        assertThat(run).containsEntry("status", "completed");
        List<LoggedRequest> sent = backend.findAll(getRequestedFor(urlPathEqualTo("/api/ai/me")));
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).getHeader("X-Agent-Channel")).isEqualTo("automation");
        assertThat(sent.get(0).getHeader("Authorization")).isEqualTo("Bearer " + runToken);
    }

    @Test
    void an_ordinary_session_keeps_app_and_cannot_claim_the_automation_channel() {
        String token = staffToken("u-" + UUID.randomUUID(), Map.of());
        // A client that labels its session «automation» gets an ordinary one.
        String sid = (String) read(client.post().uri("/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + token)
                .bodyValue(Map.of("tenant", tenant, "profileId", PROFILE, "channel", "Automation"))
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody()).get("sessionId");
        assertThat(jdbc.queryForObject("SELECT channel FROM sessions WHERE id = ?::uuid", String.class, sid))
                .isEqualTo("web");

        llm.tool("c1", "hl_me", Map.of());
        llm.say("Eres la dueña.");
        sendInteractive(sid, token, "¿quién soy?");

        List<LoggedRequest> sent = backend.findAll(getRequestedFor(urlPathEqualTo("/api/ai/me")));
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).getHeader("X-Agent-Channel")).isEqualTo("app");
    }
}
