package com.yayatechandinnovations.yayaagentic.llm;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Default LLM client. Simulates an agentic LLM well enough for tests and
 * local dev — proposes {@link LlmEvent.ToolUseProposal} for keyword
 * patterns it recognises, falls back to plain {@link LlmEvent.TokenChunk}
 * tokens otherwise. After a tool round (history contains a
 * {@link HistoryEntry.ToolResults}), it summarises the result instead of
 * proposing again.
 * <p>
 * Loads unconditionally so the engine always has a fallback. When
 * {@link AnthropicLlmClient} is present, it's {@code @Primary} and wins;
 * when it can't load (no API key → no {@code ChatModel} bean), this stub
 * keeps the engine bootable instead of crashing on a missing dependency.
 */
@Component
public class StubLlmClient implements LlmClient {

    @Override
    public Flux<LlmEvent> stream(LlmRequest request) {
        // If the most recent history entry is a ToolResults, treat this as
        // a continuation round — emit a short text summary, no more tools.
        List<HistoryEntry> history = request.history() == null ? List.of() : request.history();
        if (!history.isEmpty()
                && history.get(history.size() - 1) instanceof HistoryEntry.ToolResults tr
                && !tr.results().isEmpty()) {
            var first = tr.results().get(0);
            String summary = first.isError()
                    ? "That didn't work: " + first.value()
                    : "Done. Result: " + first.value();
            return Flux.<LlmEvent>just(new LlmEvent.TokenChunk(summary))
                    .concatWith(Mono.just(new LlmEvent.Done("end_turn")));
        }

        String userMessage = lastUserMessage(history);
        String trimmed = userMessage.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);

        boolean echoAvailable = request.availableTools() != null
                && request.availableTools().stream().anyMatch(t -> "echo".equals(t.name()));

        if (echoAvailable && (lower.equals("/echo") || lower.equals("echo"))) {
            return Flux.just(
                    (LlmEvent) new LlmEvent.ToolUseProposal(callId(), "echo", Map.of()),
                    new LlmEvent.Done("tool_use"));
        }
        if (echoAvailable && (lower.startsWith("/echo ") || lower.startsWith("echo "))) {
            String arg = trimmed.substring(lower.startsWith("/") ? 6 : 5).trim();
            return Flux.just(
                    (LlmEvent) new LlmEvent.ToolUseProposal(callId(), "echo", Map.of("text", arg)),
                    new LlmEvent.Done("tool_use"));
        }

        return Flux.fromIterable(scriptFor(trimmed))
                .delayElements(Duration.ofMillis(40))
                .<LlmEvent>map(LlmEvent.TokenChunk::new)
                .concatWith(Mono.just(new LlmEvent.Done("end_turn")));
    }

    private static String lastUserMessage(List<HistoryEntry> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i) instanceof HistoryEntry.User u) return u.content() == null ? "" : u.content();
        }
        return "";
    }

    private static String callId() {
        return "tool_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private List<String> scriptFor(String userMessage) {
        if (userMessage.equalsIgnoreCase("hello") || userMessage.equalsIgnoreCase("hi")) {
            return List.of("Hi! ", "I'm Yaya. ", "Try saying ",
                    "\"echo hello\" ", "to see a tool call.");
        }
        return List.of("I can ", "echo ", "what you ", "say. ", "Try ", "\"echo " + userMessage + "\".");
    }
}
