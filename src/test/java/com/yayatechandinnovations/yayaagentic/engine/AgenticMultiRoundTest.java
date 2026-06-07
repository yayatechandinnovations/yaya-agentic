package com.yayatechandinnovations.yayaagentic.engine;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Turn;
import com.yayatechandinnovations.yayaagentic.engine.bootstrap.HelloWorldProfileBootstrap;
import com.yayatechandinnovations.yayaagentic.llm.LlmClient;
import com.yayatechandinnovations.yayaagentic.llm.LlmClient.StopReason;
import com.yayatechandinnovations.yayaagentic.profile.StartConversationRequest;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the multi-round agentic loop: the LLM proposes a tool, the engine
 * dispatches, the LLM is invoked again with the tool result in history, and
 * it returns a textual summary. The MAX_TOOL_ROUNDS safety net is exercised
 * indirectly by a tight-loop LLM that always proposes.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, AgenticMultiRoundTest.ScriptedLlmConfig.class})
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key=",
        "spring.main.allow-bean-definition-overriding=true"
})
class AgenticMultiRoundTest {

    @Autowired ConversationEngine engine;
    @Autowired ScriptedLlm llm;

    @Test
    void llm_continues_after_tool_dispatch_with_a_summary_round() {
        llm.scriptRounds(
                // Round 1: propose the echo tool
                List.of(new LlmClient.LlmEvent.ToolUseProposal("call-1", "echo", Map.of("text", "agentic-D")),
                        new LlmClient.LlmEvent.Done(StopReason.TOOL_USE)),
                // Round 2: summarise the result
                List.of(new LlmClient.LlmEvent.TokenChunk("Done — echoed agentic-D for you."),
                        new LlmClient.LlmEvent.Done(StopReason.END_TURN))
        );
        Ids.SessionId sid = startSession();

        List<TurnEvent> turn = drain(sid, "echo agentic-D for me");

        // Round 1 events:
        TurnEvent.ToolCall call = turn.stream()
                .filter(e -> e instanceof TurnEvent.ToolCall)
                .map(e -> (TurnEvent.ToolCall) e).findFirst().orElseThrow();
        assertThat(call.tool().value()).isEqualTo("echo");

        TurnEvent.ToolResult result = turn.stream()
                .filter(e -> e instanceof TurnEvent.ToolResult)
                .map(e -> (TurnEvent.ToolResult) e).findFirst().orElseThrow();
        assertThat(result.status()).isEqualTo(Turn.ToolResult.Status.OK);

        // Round 2: the LLM's summary token must have flowed through.
        TurnEvent.Token summary = turn.stream()
                .filter(e -> e instanceof TurnEvent.Token)
                .map(e -> (TurnEvent.Token) e).reduce((a, b) -> b).orElseThrow();
        assertThat(summary.text()).contains("Done — echoed");

        // Round 2 LLM saw the tool result in its history.
        LlmClient.LlmRequest secondRequest = llm.requests().get(1);
        assertThat(secondRequest.history()).hasSize(3);   // User + Assistant(tool_use) + ToolResults
        assertThat(secondRequest.history().get(2))
                .isInstanceOf(LlmClient.HistoryEntry.ToolResults.class);
        var results = ((LlmClient.HistoryEntry.ToolResults) secondRequest.history().get(2)).results();
        assertThat(results).hasSize(1);
        assertThat(results.get(0).callId()).isEqualTo("call-1");
        assertThat(results.get(0).value().toString()).contains("agentic-D");
    }

    @Test
    void max_tool_rounds_safety_net_kicks_in() {
        // An LLM that NEVER stops proposing — engine should bail after MAX_TOOL_ROUNDS.
        llm.scriptInfiniteToolProposals();
        Ids.SessionId sid = startSession();

        List<TurnEvent> turn = drain(sid, "loop please");

        // We expect MAX_TOOL_ROUNDS tool_calls + the safety bailout token.
        long toolCalls = turn.stream().filter(e -> e instanceof TurnEvent.ToolCall).count();
        assertThat(toolCalls).isLessThanOrEqualTo(5);

        TurnEvent.Token tail = turn.stream()
                .filter(e -> e instanceof TurnEvent.Token)
                .map(e -> (TurnEvent.Token) e).reduce((a, b) -> b).orElseThrow();
        assertThat(tail.text()).containsIgnoringCase("depth limit");
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

    // ---- test LLM ---------------------------------------------------

    @TestConfiguration(proxyBeanMethods = false)
    static class ScriptedLlmConfig {
        @Bean
        @Primary
        ScriptedLlm scriptedLlm() { return new ScriptedLlm(); }
    }

    static class ScriptedLlm implements LlmClient {
        private final CopyOnWriteArrayList<LlmRequest> requests = new CopyOnWriteArrayList<>();
        private final List<List<LlmEvent>> roundScripts = new ArrayList<>();
        private boolean infinite = false;
        private int callIdx = 0;

        void scriptRounds(List<LlmEvent>... rounds) {
            roundScripts.clear();
            for (var r : rounds) roundScripts.add(r);
            callIdx = 0;
            infinite = false;
        }

        void scriptInfiniteToolProposals() {
            roundScripts.clear();
            infinite = true;
            callIdx = 0;
        }

        List<LlmRequest> requests() { return requests; }

        @Override
        public Flux<LlmEvent> stream(LlmRequest request) {
            requests.add(request);
            if (infinite) {
                return Flux.<LlmEvent>just(
                                new LlmEvent.ToolUseProposal("call-" + callIdx++, "echo", Map.of("text", "again")),
                                new LlmEvent.Done(StopReason.TOOL_USE));
            }
            List<LlmEvent> events = callIdx < roundScripts.size()
                    ? roundScripts.get(callIdx++)
                    : List.of(new LlmEvent.TokenChunk("(no more script)"), new LlmEvent.Done(StopReason.END_TURN));
            return Flux.fromIterable(events).concatWith(Mono.empty());
        }
    }
}
