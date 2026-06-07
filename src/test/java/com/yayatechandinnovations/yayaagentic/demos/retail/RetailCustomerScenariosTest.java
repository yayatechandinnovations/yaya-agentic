package com.yayatechandinnovations.yayaagentic.demos.retail;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Turn;
import com.yayatechandinnovations.yayaagentic.engine.ConversationEngine;
import com.yayatechandinnovations.yayaagentic.engine.TurnEvent;
import com.yayatechandinnovations.yayaagentic.engine.UserMessage;
import com.yayatechandinnovations.yayaagentic.engine.bootstrap.HelloWorldProfileBootstrap;
import com.yayatechandinnovations.yayaagentic.engine.bootstrap.RetailCustomerBootstrap;
import com.yayatechandinnovations.yayaagentic.llm.LlmClient;
import com.yayatechandinnovations.yayaagentic.llm.LlmClient.StopReason;
import com.yayatechandinnovations.yayaagentic.profile.StartConversationRequest;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
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
 * End-to-end retail-customer demo proofs:
 *
 * <ol>
 *   <li>Happy track — LLM proposes {@code track_shipment(ORD-1041)} for the
 *       default principal ({@code cust-1}); engine dispatches and the
 *       continuation round paraphrases the OK result.</li>
 *   <li>Ownership deny — LLM proposes {@code track_shipment(ORD-9001)};
 *       {@link OrderOwnershipAuthorizer} short-circuits the chain because
 *       ORD-9001 belongs to cust-2, the engine emits a synthetic DENIED
 *       tool_result, and the LLM paraphrases the user-safe reason rather
 *       than the audit reason.</li>
 *   <li>Confirmable return — LLM proposes {@code start_return(ORD-1042, …)};
 *       the tool's policy.confirmable forces a {@code UiHint("confirm",…)}
 *       before dispatch.</li>
 *   <li>RAG citation — a normal turn against the retail-customer profile
 *       (always-on retrieval) emits {@link TurnEvent.Citation}s for the
 *       attached return-policy + shipping-faq sources.</li>
 * </ol>
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, RetailCustomerScenariosTest.ScriptedLlmConfig.class})
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key=",
        "spring.main.allow-bean-definition-overriding=true"
})
class RetailCustomerScenariosTest {

    @Autowired ConversationEngine engine;
    @Autowired ScriptedLlm llm;

    @BeforeEach
    void resetScript() { llm.reset(); }

    @Test
    void happy_track_dispatches_and_continues() {
        llm.scriptRounds(
                List.of(new LlmClient.LlmEvent.ToolUseProposal("call-1", "track_shipment",
                                Map.of("orderId", "ORD-1041")),
                        new LlmClient.LlmEvent.Done(StopReason.TOOL_USE)),
                List.of(new LlmClient.LlmEvent.TokenChunk("Your order is on its way."),
                        new LlmClient.LlmEvent.Done(StopReason.END_TURN)));

        List<TurnEvent> events = drain("where is ORD-1041?");

        TurnEvent.ToolResult result = first(events, TurnEvent.ToolResult.class);
        assertThat(result.status()).isEqualTo(Turn.ToolResult.Status.OK);
        assertThat(result.value().toString())
                .contains("in_transit")
                .contains("UPS");
    }

    @Test
    void cross_customer_track_is_denied_and_paraphrased_by_llm() {
        llm.scriptRounds(
                List.of(new LlmClient.LlmEvent.ToolUseProposal("call-2", "track_shipment",
                                Map.of("orderId", "ORD-9001")),
                        new LlmClient.LlmEvent.Done(StopReason.TOOL_USE))
                // No round-2 script — the stub's continuation-round behavior
                // detects the DENIED tool_result and paraphrases automatically.
        );

        List<TurnEvent> events = drain("track ORD-9001 please");

        TurnEvent.ToolResult result = first(events, TurnEvent.ToolResult.class);
        assertThat(result.status()).isEqualTo(Turn.ToolResult.Status.DENIED);
        assertThat(result.value().toString())
                .as("denial payload carries the user-safe reason")
                .contains("isn't on your account");

        TurnEvent.Token tail = lastToken(events);
        assertThat(tail.text())
                .as("LLM paraphrase mentions the denial — never the audit reason")
                .containsIgnoringCase("didn't work")
                .contains("isn't on your account")
                .doesNotContain("owned by cust-2")
                .doesNotContain("ownership check");
    }

    @Test
    void start_return_pauses_for_confirm_instead_of_dispatching() {
        llm.scriptRounds(
                List.of(new LlmClient.LlmEvent.ToolUseProposal("call-3", "start_return",
                                Map.of("orderId", "ORD-1042", "reason", "doesn't fit")),
                        new LlmClient.LlmEvent.Done(StopReason.TOOL_USE)));

        List<TurnEvent> events = drain("return ORD-1042 — it doesn't fit");

        TurnEvent.UiHint confirm = first(events, TurnEvent.UiHint.class);
        assertThat(confirm.kind()).isEqualTo("confirm");
        assertThat(confirm.payload().get("toolId")).isEqualTo("start_return");

        // No tool_result event should have fired — the dispatch is paused.
        boolean anyToolResult = events.stream()
                .anyMatch(e -> e instanceof TurnEvent.ToolResult);
        assertThat(anyToolResult)
                .as("confirmable tools must NOT dispatch on the first round")
                .isFalse();
    }

    @Test
    void retrieval_emits_citation_events_for_attached_policy_sources() {
        // No LLM script: the stub's default script emits plain tokens.
        // We're asserting the engine ran retrieval BEFORE the LLM round.
        List<TurnEvent> events = drain("what's the return window?");

        long citationCount = events.stream()
                .filter(e -> e instanceof TurnEvent.Citation)
                .count();
        assertThat(citationCount)
                .as("retail-customer has the return-policy + shipping-faq "
                        + "sources attached; always-on gating should surface chunks")
                .isGreaterThan(0);
    }

    // ---- helpers ----------------------------------------------------

    private List<TurnEvent> drain(String text) {
        Ids.SessionId sid = engine.start(
                new StartConversationRequest(
                        RetailCustomerBootstrap.DEFAULT_TENANT,
                        Optional.of(RetailCustomerBootstrap.RETAIL_CUSTOMER_PROFILE),
                        "web", Map.of()),
                new AuthContext(RetailCustomerBootstrap.DEFAULT_TENANT, Map.of(), Optional.empty())
        ).session().id();

        List<TurnEvent> out = new ArrayList<>();
        engine.send(sid, new UserMessage(text, Map.of()),
                new AuthContext(RetailCustomerBootstrap.DEFAULT_TENANT, Map.of(), Optional.empty()))
                .doOnNext(out::add)
                .blockLast();
        return out;
    }

    @SuppressWarnings("unchecked")
    private static <T extends TurnEvent> T first(List<TurnEvent> events, Class<T> type) {
        return (T) events.stream()
                .filter(type::isInstance)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + type.getSimpleName() + " event in stream"));
    }

    private static TurnEvent.Token lastToken(List<TurnEvent> events) {
        return events.stream()
                .filter(e -> e instanceof TurnEvent.Token)
                .map(e -> (TurnEvent.Token) e)
                .reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError("no Token event in stream"));
    }

    // ---- scripted LLM ------------------------------------------------

    @TestConfiguration(proxyBeanMethods = false)
    static class ScriptedLlmConfig {
        @Bean
        @Primary
        ScriptedLlm scriptedLlm() { return new ScriptedLlm(); }
    }

    static class ScriptedLlm implements LlmClient {
        private final CopyOnWriteArrayList<LlmRequest> requests = new CopyOnWriteArrayList<>();
        private final List<List<LlmEvent>> roundScripts = new ArrayList<>();
        private int callIdx = 0;

        void reset() {
            requests.clear();
            roundScripts.clear();
            callIdx = 0;
        }

        @SafeVarargs
        final void scriptRounds(List<LlmEvent>... rounds) {
            roundScripts.clear();
            for (var r : rounds) roundScripts.add(r);
            callIdx = 0;
        }

        @Override
        public Flux<LlmEvent> stream(LlmRequest request) {
            requests.add(request);

            // If we're past the scripted rounds AND the most recent history
            // entry is a tool_result, do what the production stub does:
            // paraphrase the result. That makes the deny-test simpler — we
            // script only round 1 and let the engine + stub-style fallback
            // produce the paraphrase.
            List<LlmEvent> events;
            if (callIdx < roundScripts.size()) {
                events = roundScripts.get(callIdx++);
            } else {
                events = paraphraseLastToolResult(request);
                callIdx++;
            }
            return Flux.fromIterable(events).concatWith(Mono.empty());
        }

        private static List<LlmEvent> paraphraseLastToolResult(LlmRequest req) {
            List<HistoryEntry> history = req.history();
            if (!history.isEmpty()
                    && history.get(history.size() - 1) instanceof HistoryEntry.ToolResults tr
                    && !tr.results().isEmpty()) {
                var first = tr.results().get(0);
                String text = first.isError()
                        ? "That didn't work: " + first.value()
                        : "Here you go: " + first.value();
                return List.of(new LlmEvent.TokenChunk(text), new LlmEvent.Done(StopReason.END_TURN));
            }
            return List.of(new LlmEvent.TokenChunk("(no more script)"),
                    new LlmEvent.Done(StopReason.END_TURN));
        }
    }
}
