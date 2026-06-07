package com.yayatechandinnovations.yayaagentic.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real Anthropic adapter built on Spring AI 1.0's {@code ChatModel} and
 * the {@code AnthropicChatOptions.toolCallbacks(...)} pathway with
 * {@code internalToolExecutionEnabled(false)} — Spring AI passes our
 * tool definitions to Anthropic but does NOT auto-execute them; the
 * engine handles dispatch through its own AuthZ / schema / confirmable
 * pipeline.
 *
 * <p>{@code history} is translated to the {@code UserMessage} /
 * {@code AssistantMessage(text, toolCalls)} / {@code ToolResponseMessage}
 * triplet Anthropic expects for tool-result rounds. The stream is scanned
 * for text tokens (forwarded as {@link LlmEvent.TokenChunk}) and final
 * tool calls (emitted at stream end as {@link LlmEvent.ToolUseProposal}s).
 * The terminating {@link LlmEvent.Done} carries {@code stop_reason} so
 * the engine knows whether to loop.</p>
 */
@Component
@ConditionalOnProperty(name = "yaya.agentic.llm.provider", havingValue = "anthropic")
public class AnthropicLlmClient implements LlmClient {

    private static final TypeReference<Map<String, Object>> STRING_MAP = new TypeReference<>() {};

    private final ChatModel chatModel;
    private final ObjectMapper json;

    public AnthropicLlmClient(ChatModel chatModel, ObjectMapper json) {
        this.chatModel = chatModel;
        this.json = json;
    }

    @Override
    public Flux<LlmEvent> stream(LlmRequest request) {
        Prompt prompt = buildPrompt(request);

        // Spring AI populates ToolCalls as the assistant message materialises;
        // collect them deduped by id and emit at the end.
        LinkedHashMap<String, AssistantMessage.ToolCall> seenCalls = new LinkedHashMap<>();

        Flux<LlmEvent> tokens = chatModel.stream(prompt)
                .concatMap(response -> {
                    AssistantMessage out = response.getResult() == null ? null
                            : response.getResult().getOutput();
                    if (out == null) return Flux.<LlmEvent>empty();

                    List<AssistantMessage.ToolCall> calls = out.getToolCalls();
                    if (calls != null) {
                        for (var tc : calls) seenCalls.putIfAbsent(tc.id(), tc);
                    }

                    String text = out.getText();
                    return text == null || text.isEmpty()
                            ? Flux.<LlmEvent>empty()
                            : Flux.<LlmEvent>just(new LlmEvent.TokenChunk(text));
                });

        Mono<List<LlmEvent>> tail = Mono.fromSupplier(() -> {
            List<LlmEvent> out = new ArrayList<>();
            for (var tc : seenCalls.values()) {
                out.add(new LlmEvent.ToolUseProposal(tc.id(), tc.name(), parseArgs(tc.arguments())));
            }
            String stop = seenCalls.isEmpty() ? "end_turn" : "tool_use";
            out.add(new LlmEvent.Done(stop));
            return out;
        });

        return tokens.concatWith(tail.flatMapMany(Flux::fromIterable));
    }

    // ---- Prompt translation -------------------------------------------

    private Prompt buildPrompt(LlmRequest request) {
        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
        String prefix = request.cacheablePrefix();
        String suffix = request.variableSuffix();
        if (prefix != null && !prefix.isEmpty()) messages.add(new SystemMessage(prefix));
        if (suffix != null && !suffix.isEmpty()) messages.add(new SystemMessage(suffix));
        messages.addAll(historyToMessages(request.history()));

        AnthropicChatOptions options = AnthropicChatOptions.builder()
                .toolCallbacks(toCallbacks(request.availableTools()))
                .internalToolExecutionEnabled(false)
                .build();
        return new Prompt(messages, options);
    }

    private List<org.springframework.ai.chat.messages.Message> historyToMessages(List<HistoryEntry> history) {
        List<org.springframework.ai.chat.messages.Message> out = new ArrayList<>();
        if (history == null) return out;
        for (HistoryEntry entry : history) {
            switch (entry) {
                case HistoryEntry.User u -> out.add(new UserMessage(u.content() == null ? "" : u.content()));
                case HistoryEntry.Assistant a -> {
                    List<AssistantMessage.ToolCall> calls = a.toolCalls() == null ? List.of()
                            : a.toolCalls().stream()
                                    .map(spec -> new AssistantMessage.ToolCall(
                                            spec.callId(), "function",
                                            spec.toolName(), writeJson(spec.args())))
                                    .toList();
                    out.add(new AssistantMessage(a.content() == null ? "" : a.content(), Map.of(), calls));
                }
                case HistoryEntry.ToolResults tr -> {
                    List<ToolResponseMessage.ToolResponse> responses = tr.results().stream()
                            .map(r -> new ToolResponseMessage.ToolResponse(
                                    r.callId(), "", writeJson(r.value())))
                            .toList();
                    out.add(new ToolResponseMessage(responses));
                }
            }
        }
        return out;
    }

    private List<ToolCallback> toCallbacks(List<ToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) return List.of();
        return tools.stream()
                .map(td -> (ToolCallback) FunctionToolCallback
                        .builder(td.name(), (Map<String, Object> args,
                                             org.springframework.ai.chat.model.ToolContext ctx) -> {
                            // internalToolExecutionEnabled=false means Spring AI
                            // should never call this. If it does, our integration
                            // is broken — fail loudly so the issue surfaces.
                            throw new IllegalStateException(
                                    "Spring AI attempted to auto-execute tool '" + td.name()
                                    + "' — internalToolExecutionEnabled flag did not take effect");
                        })
                        .description(td.description() == null ? td.name() : td.description())
                        .inputSchema(td.inputSchemaJson())
                        .inputType(new ParameterizedTypeReference<Map<String, Object>>() {})
                        .build())
                .toList();
    }

    // ---- JSON helpers --------------------------------------------------

    private Map<String, Object> parseArgs(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) return Map.of();
        try {
            return json.readValue(argumentsJson, STRING_MAP);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return value == null ? "" : json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }
}
