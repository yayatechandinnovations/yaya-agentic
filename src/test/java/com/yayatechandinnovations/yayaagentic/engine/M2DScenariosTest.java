package com.yayatechandinnovations.yayaagentic.engine;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.core.Turn;
import com.yayatechandinnovations.yayaagentic.engine.bootstrap.HelloWorldProfileBootstrap;
import com.yayatechandinnovations.yayaagentic.engine.bootstrap.M0Catalog;
import com.yayatechandinnovations.yayaagentic.profile.StartConversationRequest;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import com.yayatechandinnovations.yayaagentic.tool.ToolDescriptor;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandlerRef;
import com.yayatechandinnovations.yayaagentic.tool.ToolPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M2-D scenarios: capability follow-up hints emitted as a
 * {@code UiHint("quick_replies", …)} event after a clean tool dispatch,
 * and the confirmable two-phase preview → confirm / cancel flow.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
class M2DScenariosTest {

    @Autowired ConversationEngine engine;
    @Autowired M0Catalog catalog;

    /**
     * Replace the echo tool's descriptor with a confirmable variant for the
     * lifetime of each test. The bootstrap re-runs on every JVM but tests
     * within one JVM run sequentially, so we restore the non-confirmable
     * descriptor in @AfterEach via a fresh registration.
     */
    @BeforeEach
    void reset() {
        // Default (non-confirmable) descriptor — restored before each test.
        catalog.registerTool(echoDescriptor(false));
    }

    @Test
    void follow_up_hints_emit_as_a_quick_replies_uihint_after_dispatch() {
        Ids.SessionId sid = startSession();

        List<TurnEvent> turn = drain(sid, "echo phase-d");

        // Find the quick-replies UiHint event.
        TurnEvent.UiHint hint = turn.stream()
                .filter(e -> e instanceof TurnEvent.UiHint)
                .map(e -> (TurnEvent.UiHint) e)
                .findFirst().orElseThrow();
        assertThat(hint.kind()).isEqualTo("quick_replies");
        @SuppressWarnings("unchecked")
        List<String> items = (List<String>) hint.payload().get("items");
        assertThat(items).contains("echo something else", "what else can you do?");
    }

    @Test
    void confirmable_tool_pauses_for_yes_before_dispatch() {
        // Make echo confirmable for this scenario.
        catalog.registerTool(echoDescriptor(true));
        Ids.SessionId sid = startSession();

        List<TurnEvent> preview = drain(sid, "echo confirm-me");
        // Saw a tool_call and a UiHint(confirm), but NO tool_result yet.
        assertThat(preview).filteredOn(e -> e instanceof TurnEvent.ToolResult).isEmpty();
        TurnEvent.UiHint confirm = preview.stream()
                .filter(e -> e instanceof TurnEvent.UiHint && ((TurnEvent.UiHint) e).kind().equals("confirm"))
                .map(e -> (TurnEvent.UiHint) e)
                .findFirst().orElseThrow();
        assertThat(confirm.payload()).containsKey("toolId").containsKey("args");

        // "yes" resumes the dispatch — now we see the tool_result.
        List<TurnEvent> approved = drain(sid, "yes");
        TurnEvent.ToolResult result = approved.stream()
                .filter(e -> e instanceof TurnEvent.ToolResult)
                .map(e -> (TurnEvent.ToolResult) e)
                .findFirst().orElseThrow();
        assertThat(result.status()).isEqualTo(Turn.ToolResult.Status.OK);
        assertThat(result.value().toString()).contains("confirm-me");
    }

    @Test
    void confirmable_tool_cancels_on_no() {
        catalog.registerTool(echoDescriptor(true));
        Ids.SessionId sid = startSession();
        drain(sid, "echo cancel-me");

        List<TurnEvent> cancelled = drain(sid, "no");

        // No tool_result (we never dispatched), but a Token explaining the cancel.
        assertThat(cancelled).filteredOn(e -> e instanceof TurnEvent.ToolResult).isEmpty();
        TurnEvent.Token token = cancelled.stream()
                .filter(e -> e instanceof TurnEvent.Token)
                .map(e -> (TurnEvent.Token) e)
                .findFirst().orElseThrow();
        assertThat(token.text().toLowerCase()).contains("cancel");
    }

    @Test
    void unclear_reply_to_confirm_drops_pending_and_pivots() {
        catalog.registerTool(echoDescriptor(true));
        Ids.SessionId sid = startSession();
        drain(sid, "echo pivot-me");

        // Reply with something that's not yes/no — engine drops pending and
        // re-runs the intent path. The text is unclassified so we land in
        // the LLM (stub) reply path; importantly, no dispatch happens.
        List<TurnEvent> pivot = drain(sid, "hmm, what does this do?");

        assertThat(pivot).filteredOn(e -> e instanceof TurnEvent.ToolResult).isEmpty();
        assertThat(pivot).filteredOn(e -> e instanceof TurnEvent.Token).isNotEmpty();
    }

    // ---- helpers ----------------------------------------------------

    private Ids.SessionId startSession() {
        return engine.start(
                new StartConversationRequest(
                        HelloWorldProfileBootstrap.DEFAULT_TENANT,
                        Optional.of(HelloWorldProfileBootstrap.HELLO_WORLD_PROFILE),
                        "web", Map.of()),
                new AuthContext(HelloWorldProfileBootstrap.DEFAULT_TENANT, Map.of(), Optional.empty())
        ).session().id();
    }

    private List<TurnEvent> drain(Ids.SessionId sid, String text) {
        List<TurnEvent> out = new ArrayList<>();
        engine.send(sid, new UserMessage(text, Map.of()),
                new AuthContext(HelloWorldProfileBootstrap.DEFAULT_TENANT, Map.of(), Optional.empty()))
                .doOnNext(out::add)
                .blockLast();
        return out;
    }

    private static ToolDescriptor echoDescriptor(boolean confirmable) {
        return new ToolDescriptor(
                HelloWorldProfileBootstrap.ECHO,
                "{\"type\":\"object\",\"required\":[\"text\"],\"properties\":{\"text\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\",\"properties\":{\"echo\":{\"type\":\"string\"}}}",
                PermissionRequirement.none(),
                new ToolHandlerRef.Bean("echoTool"),
                new ToolPolicy(Duration.ofSeconds(10), 0, true, confirmable));
    }
}
