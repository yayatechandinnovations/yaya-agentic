package com.yayatechandinnovations.yayaagentic.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Value;
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
 * OpenAI adapter, structurally identical to {@link AnthropicLlmClient}.
 * Builds on Spring AI 1.0's {@code ChatModel} + {@code OpenAiChatOptions
 * .toolCallbacks(...)} pathway with {@code internalToolExecutionEnabled(false)}
 * — Spring AI passes our tool definitions to OpenAI but does NOT execute
 * them. The engine handles dispatch through its AuthZ + schema + confirmable
 * pipeline, same as for Anthropic.
 *
 * <p>The fact that this class is structurally identical to the Anthropic
 * one is the point: the engine's {@link LlmClient} SPI hides the provider,
 * so adding a third (Bedrock, Gemini, …) is implementing this same shape
 * once more.</p>
 *
 * <p>OpenAI's wire-level {@code finish_reason} can be {@code stop},
 * {@code tool_calls}, {@code length}, etc. — but Spring AI doesn't surface
 * it on the streamed response. We infer the same way the Anthropic client
 * does: if the assistant produced any tool_calls, the model paused for
 * dispatch ({@link StopReason#TOOL_USE}); otherwise it ended its reply
 * ({@link StopReason#END_TURN}). MAX_TOKENS would require a deeper hook
 * into the response metadata; it's not load-bearing for the engine loop.</p>
 *
 * <p>Also the {@link StructuredLlmClient} for {@code yaya.agentic.llm.provider=openai}:
 * the same bean serves both interfaces, so the reply suggester and
 * {@code POST /v1/structured-completions} find a real provider on an OpenAI
 * deployment instead of only the stub.</p>
 */
@Component
@org.springframework.context.annotation.Primary
@ConditionalOnProperty(name = "yaya.agentic.llm.provider", havingValue = "openai")
public class OpenAiLlmClient implements LlmClient, StructuredLlmClient {

    private static final TypeReference<Map<String, Object>> STRING_MAP = new TypeReference<>() {};

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(OpenAiLlmClient.class);

    private final ChatModel chatModel;
    private final ObjectMapper json;

    // The model the deployment runs (same key Spring AI's OpenAI autoconfig
    // binds). Only reported back by the structured-completion path when the
    // provider's response carries no model id. Field injection so plain `new`
    // in unit tests needs no configuration.
    @Value("${spring.ai.openai.chat.options.model:}")
    private String configuredModel;

    public OpenAiLlmClient(ChatModel chatModel, ObjectMapper json) {
        this.chatModel = chatModel;
        this.json = json;
        LOG.info("OpenAiLlmClient instantiated — backed by ChatModel={}", chatModel.getClass().getSimpleName());
    }

    @Override
    public Flux<LlmEvent> stream(LlmRequest request) {
        Prompt prompt = buildPrompt(request);

        LinkedHashMap<String, AssistantMessage.ToolCall> seenCalls = new LinkedHashMap<>();
        java.util.concurrent.atomic.AtomicReference<Usage> lastUsage =
                new java.util.concurrent.atomic.AtomicReference<>();

        Flux<LlmEvent> tokens = chatModel.stream(prompt)
                .concatMap(response -> {
                    captureUsage(response, lastUsage);
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
            StopReason stop = seenCalls.isEmpty() ? StopReason.END_TURN : StopReason.TOOL_USE;
            out.add(new LlmEvent.Done(stop, lastUsage.get()));
            return out;
        });

        return tokens.concatWith(tail.flatMapMany(Flux::fromIterable));
    }

    /** Best-effort: the last chunk carrying usage is the settled count. Never throws. */
    private static void captureUsage(org.springframework.ai.chat.model.ChatResponse response,
                                     java.util.concurrent.atomic.AtomicReference<Usage> sink) {
        try {
            if (response == null || response.getMetadata() == null) return;
            var usage = response.getMetadata().getUsage();
            if (usage == null) return;
            long in = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
            long out = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
            if (in + out > 0) sink.set(new Usage(in, out));
        } catch (RuntimeException ignored) {
            // Metering must never break a chat turn.
        }
    }

    // ---- Structured completion (single shot, forced answer shape) ------

    @Override
    public String provider() { return "openai"; }

    @Override
    public StructuredResult complete(StructuredRequest request) {
        // Streamed and collected, not chatModel.call(): streaming is the
        // transport every chat turn already uses in production (see stream()),
        // and Spring AI merges the tool-call deltas into one chunk for us.
        List<org.springframework.ai.chat.model.ChatResponse> chunks;
        try {
            chunks = chatModel.stream(structuredPrompt(request)).collectList().block();
        } catch (RuntimeException e) {
            // Class name only — provider error bodies can echo request content.
            throw new StructuredLlmException("openai call failed: " + e.getClass().getSimpleName(), e);
        }
        if (chunks == null || chunks.isEmpty()) throw new StructuredLlmException("openai returned no response", null);

        Map<String, Object> output = null;
        boolean truncated = false;
        long input = 0, outputTokens = 0, cacheRead = 0;
        String model = null;
        for (var response : chunks) {
            if (response == null) continue;
            for (var generation : response.getResults()) {
                if (generation == null) continue;
                var meta = generation.getMetadata();
                // Spring AI reports the enum name (LENGTH), the wire says `length`.
                if (meta != null && "length".equalsIgnoreCase(meta.getFinishReason())) truncated = true;
                AssistantMessage out = generation.getOutput();
                if (out == null || out.getToolCalls() == null || output != null) continue;
                for (var tc : out.getToolCalls()) {
                    if (request.toolName().equals(tc.name())) {
                        output = parseStructuredArgs(tc.arguments());
                        break;
                    }
                }
            }
            var metadata = response.getMetadata();
            if (metadata == null) continue;
            if (metadata.getModel() != null && !metadata.getModel().isBlank()) model = metadata.getModel();
            var usage = metadata.getUsage();
            if (usage == null) continue;
            if (usage.getNativeUsage() instanceof OpenAiApi.Usage u) {
                // Unlike Anthropic, OpenAI's prompt_tokens already INCLUDES the
                // cached ones; cached_tokens is a breakdown, not an addend.
                input = nz(u.promptTokens());
                outputTokens = nz(u.completionTokens());
                cacheRead = u.promptTokensDetails() == null ? 0 : nz(u.promptTokensDetails().cachedTokens());
            } else if (nz(usage.getPromptTokens()) + nz(usage.getCompletionTokens()) > 0) {
                input = nz(usage.getPromptTokens());
                outputTokens = nz(usage.getCompletionTokens());
            }
        }
        if (model == null || model.isBlank()) model = configuredModel;
        return new StructuredResult(output, truncated, input, outputTokens, cacheRead, 0, model);
    }

    /** Package-private for unit testing. */
    Prompt structuredPrompt(StructuredRequest request) {
        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
        if (request.system() != null && !request.system().isBlank()) {
            messages.add(new SystemMessage(request.system()));
        }
        String instruction = AnthropicLlmClient.STRUCTURED_INSTRUCTION.formatted(request.toolName());
        if (request.retryHint() != null && !request.retryHint().isBlank()) {
            instruction += "\nYour previous answer was rejected: " + request.retryHint()
                    + "\nAnswer again and fix it.";
        }
        messages.add(new SystemMessage(instruction));
        messages.add(new UserMessage(request.input()));

        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                .toolCallbacks(toCallbacks(List.of(new ToolDefinition(request.toolName(),
                        "Return the final structured result.", request.schemaJson()))))
                .internalToolExecutionEnabled(false)
                // max_completion_tokens, not max_tokens: the reasoning models
                // (o-series, gpt-5) reject the legacy knob with a 400, and the
                // gpt-4o family accepts both.
                .maxCompletionTokens(request.maxOutputTokens())
                .toolChoice(OpenAiApi.ChatCompletionRequest.ToolChoiceBuilder.function(request.toolName()));
        if (request.model() != null && !request.model().isBlank()) options.model(request.model());
        return new Prompt(messages, options.build());
    }

    /** Unlike {@link #parseArgs}: unparseable arguments are "no answer", not an empty object. */
    private Map<String, Object> parseStructuredArgs(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) return null;
        try {
            return json.readValue(argumentsJson, STRING_MAP);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static long nz(Integer v) { return v == null ? 0 : v; }

    // ---- Prompt translation -------------------------------------------

    private Prompt buildPrompt(LlmRequest request) {
        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
        String prefix = request.cacheablePrefix();
        String suffix = request.variableSuffix();
        if (prefix != null && !prefix.isEmpty()) messages.add(new SystemMessage(prefix));
        if (suffix != null && !suffix.isEmpty()) messages.add(new SystemMessage(suffix));
        messages.addAll(historyToMessages(request.history()));

        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                .toolCallbacks(toCallbacks(request.availableTools()))
                .internalToolExecutionEnabled(false);
        if (request.model() != null && !request.model().isBlank()) options.model(request.model());
        return new Prompt(messages, options.build());
    }

    private List<org.springframework.ai.chat.messages.Message> historyToMessages(List<HistoryEntry> history) {
        List<org.springframework.ai.chat.messages.Message> out = new ArrayList<>();
        if (history == null) return out;
        for (HistoryEntry entry : history) {
            switch (entry) {
                case HistoryEntry.User u -> {
                    // Never send a blank user message (defensive; the engine already
                    // drops text-less turns) — keeps parity with the Anthropic client.
                    String c = u.content();
                    boolean hasImages = u.images() != null && !u.images().isEmpty();
                    if (hasImages) {
                        List<org.springframework.ai.content.Media> media = u.images().stream()
                                .map(img -> new org.springframework.ai.content.Media(
                                        org.springframework.util.MimeTypeUtils.parseMimeType(img.contentType()),
                                        new org.springframework.core.io.ByteArrayResource(
                                                java.util.Base64.getDecoder().decode(img.base64Data()))))
                                .toList();
                        out.add(UserMessage.builder()
                                .text(c == null || c.isBlank() ? "[image]" : c)
                                .media(media)
                                .build());
                    } else if (c != null && !c.isBlank()) {
                        out.add(new UserMessage(c));
                    }
                }
                case HistoryEntry.Assistant a -> {
                    List<AssistantMessage.ToolCall> calls = a.toolCalls() == null ? List.of()
                            : a.toolCalls().stream()
                                    .map(spec -> new AssistantMessage.ToolCall(
                                            spec.callId(), "function",
                                            spec.toolName(), writeJson(spec.args())))
                                    .toList();
                    out.add(AssistantMessage.builder()
                            .content(a.content() == null ? "" : a.content())
                            .toolCalls(calls)
                            .build());
                }
                case HistoryEntry.ToolResults tr -> {
                    List<ToolResponseMessage.ToolResponse> responses = tr.results().stream()
                            .map(r -> new ToolResponseMessage.ToolResponse(
                                    r.callId(), "", writeJson(r.value())))
                            .toList();
                    out.add(ToolResponseMessage.builder().responses(responses).build());
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
