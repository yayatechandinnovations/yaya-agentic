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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the engine reacts to LLM-proposed tool calls, not a hardcoded
 * keyword path. A captured-stub LLM is swapped in: it records what tools
 * the engine offered, then deterministically proposes one of them.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, AgenticLoopTest.CapturingLlmConfig.class})
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",      // wins for any non-overridden Authorizer / etc.
        "spring.ai.anthropic.api-key=",
        "spring.main.allow-bean-definition-overriding=true"
})
class AgenticLoopTest {

    @Autowired ConversationEngine engine;
    @Autowired CapturingLlmClient llm;

    @Test
    void engine_advertises_profile_tools_to_the_llm() {
        Ids.SessionId sid = startSession();
        drain(sid, "hello");

        LlmClient.LlmRequest seen = llm.firstRequest();
        assertThat(seen).isNotNull();
        assertThat(seen.availableTools())
                .extracting(LlmClient.ToolDefinition::name)
                .contains("echo");
        var echo = seen.availableTools().stream()
                .filter(t -> t.name().equals("echo")).findFirst().orElseThrow();
        assertThat(echo.inputSchemaJson()).contains("\"text\"");
    }

    @Test
    void llm_proposed_tool_use_drives_dispatch() {
        llm.scriptToolProposal("echo", Map.of("text", "agentic"));
        Ids.SessionId sid = startSession();

        List<TurnEvent> turn = drain(sid, "please echo something for me");

        // The engine emitted tool_call + tool_result + token — proving it
        // dispatched the LLM's proposal, not a heuristic pattern.
        TurnEvent.ToolCall call = turn.stream()
                .filter(e -> e instanceof TurnEvent.ToolCall)
                .map(e -> (TurnEvent.ToolCall) e).findFirst().orElseThrow();
        assertThat(call.tool().value()).isEqualTo("echo");

        TurnEvent.ToolResult result = turn.stream()
                .filter(e -> e instanceof TurnEvent.ToolResult)
                .map(e -> (TurnEvent.ToolResult) e).findFirst().orElseThrow();
        assertThat(result.status()).isEqualTo(Turn.ToolResult.Status.OK);
        assertThat(result.value().toString()).contains("agentic");
    }

    @Test
    void llm_proposed_tool_with_missing_required_args_triggers_elicitation() {
        llm.scriptToolProposal("echo", Map.of());   // no "text" → schema validator
        Ids.SessionId sid = startSession();

        List<TurnEvent> turn = drain(sid, "echo something");

        // No tool_call emitted — engine hit NeedsInput and asked instead.
        assertThat(turn).filteredOn(e -> e instanceof TurnEvent.ToolCall).isEmpty();
        TurnEvent.Token question = turn.stream()
                .filter(e -> e instanceof TurnEvent.Token)
                .map(e -> (TurnEvent.Token) e).reduce((a, b) -> b).orElseThrow();
        assertThat(question.text().toLowerCase()).contains("text");
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
    static class CapturingLlmConfig {
        @Bean
        @Primary
        CapturingLlmClient capturingLlmClient() {
            return new CapturingLlmClient();
        }
    }

    static class CapturingLlmClient implements LlmClient {
        private final CopyOnWriteArrayList<LlmRequest> requests = new CopyOnWriteArrayList<>();
        private final AtomicReference<LlmEvent.ToolUseProposal> scripted = new AtomicReference<>();

        void scriptToolProposal(String toolName, Map<String, Object> args) {
            scripted.set(new LlmEvent.ToolUseProposal("test-call", toolName, args));
        }

        LlmRequest firstRequest() {
            return requests.isEmpty() ? null : requests.get(0);
        }

        @Override
        public Flux<LlmEvent> stream(LlmRequest request) {
            requests.add(request);
            LlmEvent.ToolUseProposal pending = scripted.getAndSet(null);
            if (pending != null) {
                return Flux.just((LlmEvent) pending, new LlmEvent.Done(StopReason.TOOL_USE));
            }
            return Flux.<LlmEvent>just(new LlmEvent.TokenChunk("Hi from the test LLM."))
                    .concatWith(Mono.just(new LlmEvent.Done(StopReason.END_TURN)));
        }
    }
}
