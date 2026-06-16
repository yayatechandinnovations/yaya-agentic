package com.yayatechandinnovations.yayaagentic.recorder.summarizer;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Turn;
import com.yayatechandinnovations.yayaagentic.llm.LlmClient;
import com.yayatechandinnovations.yayaagentic.recorder.ConversationSummarizer;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LlmConversationSummarizerTest {

    @Test
    void calls_llm_with_window_and_returns_text() {
        AtomicReference<LlmClient.LlmRequest> captured = new AtomicReference<>();
        LlmClient llm = req -> {
            captured.set(req);
            return Flux.just(
                    new LlmClient.LlmEvent.TokenChunk("User ordered pizza."),
                    new LlmClient.LlmEvent.TokenChunk(" Awaiting size."),
                    new LlmClient.LlmEvent.Done(LlmClient.StopReason.END_TURN));
        };

        LlmConversationSummarizer summarizer = new LlmConversationSummarizer(llm);
        ConversationSummarizer.SummarizedTurn out = summarizer.summarize(
                List.of(userTurn(0, "I'd like a pizza"), assistantTurn(1, "Which size?")),
                new ConversationSummarizer.SummarizationContext(0, Map.of()));

        assertThat(out.text()).isEqualTo("User ordered pizza. Awaiting size.");
        assertThat(out.metadata()).containsEntry("source", "llm");
        LlmClient.LlmRequest seen = captured.get();
        assertThat(seen.cacheablePrefix()).contains("compact agent conversations");
        assertThat(seen.history()).hasSize(1);
        assertThat(((LlmClient.HistoryEntry.User) seen.history().get(0)).content())
                .contains("I'd like a pizza")
                .contains("Which size?");
    }

    @Test
    void includes_prior_summary_in_prompt_when_provided() {
        AtomicReference<LlmClient.LlmRequest> captured = new AtomicReference<>();
        LlmClient llm = req -> {
            captured.set(req);
            return Flux.just(
                    new LlmClient.LlmEvent.TokenChunk("Updated summary."),
                    new LlmClient.LlmEvent.Done(LlmClient.StopReason.END_TURN));
        };

        LlmConversationSummarizer summarizer = new LlmConversationSummarizer(llm);
        summarizer.summarize(
                List.of(userTurn(5, "Make it large")),
                new ConversationSummarizer.SummarizationContext(0,
                        Map.of("priorSummary", "User ordered pizza.")));

        String rendered = ((LlmClient.HistoryEntry.User) captured.get().history().get(0)).content();
        assertThat(rendered).contains("[PRIOR SUMMARY]");
        assertThat(rendered).contains("User ordered pizza.");
        assertThat(rendered).contains("Make it large");
    }

    @Test
    void falls_back_to_heuristic_when_llm_throws() {
        LlmClient llm = req -> Flux.error(new RuntimeException("api down"));

        LlmConversationSummarizer summarizer = new LlmConversationSummarizer(llm);
        ConversationSummarizer.SummarizedTurn out = summarizer.summarize(
                List.of(userTurn(0, "First message"), assistantTurn(1, "First reply")),
                new ConversationSummarizer.SummarizationContext(0, Map.of()));

        assertThat(out.metadata()).containsEntry("source", "heuristic");
        assertThat(out.text()).contains("First message");
        assertThat(out.text()).contains("First reply");
    }

    @Test
    void heuristic_includes_tool_outcome_counts() {
        LlmClient llm = req -> Flux.error(new RuntimeException("api down"));
        LlmConversationSummarizer summarizer = new LlmConversationSummarizer(llm);

        Turn assistantWithTools = new Turn(
                new Ids.TurnId("t2"), new Ids.SessionId("s"), 1,
                Turn.Role.ASSISTANT, "Working on it",
                List.of(new Turn.ToolCall("c1", new Ids.ToolId("echo"), Map.of())),
                List.of(new Turn.ToolResult("c1", Turn.ToolResult.Status.OK, "ok", null),
                        new Turn.ToolResult("c2", Turn.ToolResult.Status.DENIED, null, "no")),
                List.of(), null, Map.of(), Instant.now());

        ConversationSummarizer.SummarizedTurn out = summarizer.summarize(
                List.of(userTurn(0, "go"), assistantWithTools),
                new ConversationSummarizer.SummarizationContext(0, Map.of()));

        assertThat(out.text()).contains("OK=1").contains("DENIED=1");
    }

    @Test
    void treats_blank_llm_response_as_failure_and_falls_back() {
        LlmClient llm = req -> Flux.just(
                new LlmClient.LlmEvent.TokenChunk("   "),
                new LlmClient.LlmEvent.Done(LlmClient.StopReason.END_TURN));
        LlmConversationSummarizer summarizer = new LlmConversationSummarizer(llm);

        ConversationSummarizer.SummarizedTurn out = summarizer.summarize(
                List.of(userTurn(0, "ask")),
                new ConversationSummarizer.SummarizationContext(0, Map.of()));

        assertThat(out.metadata()).containsEntry("source", "heuristic");
    }

    // ---- fixtures ---------------------------------------------------

    private static Turn userTurn(int idx, String text) {
        return new Turn(new Ids.TurnId("u" + idx), new Ids.SessionId("s"), idx,
                Turn.Role.USER, text, List.of(), List.of(), List.of(), null, Map.of(), Instant.now());
    }

    private static Turn assistantTurn(int idx, String text) {
        return new Turn(new Ids.TurnId("a" + idx), new Ids.SessionId("s"), idx,
                Turn.Role.ASSISTANT, text, List.of(), List.of(), List.of(), null, Map.of(), Instant.now());
    }
}
