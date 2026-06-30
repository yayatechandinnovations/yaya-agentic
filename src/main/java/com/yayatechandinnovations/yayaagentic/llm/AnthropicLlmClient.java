package com.yayatechandinnovations.yayaagentic.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheStrategy;
import org.springframework.ai.anthropic.api.AnthropicCacheTtl;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
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
import java.util.concurrent.atomic.AtomicReference;

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
@org.springframework.context.annotation.Primary
@ConditionalOnProperty(name = "yaya.agentic.llm.provider", havingValue = "anthropic")
public class AnthropicLlmClient implements LlmClient {
    // NOTE: an earlier version added @ConditionalOnBean(ChatModel.class) so
    // boot would fall back to the stub if the Anthropic autoconfig couldn't
    // produce a ChatModel. That doesn't work for @Component classes —
    // ConditionalOnBean is evaluated during component scanning, before the
    // autoconfig has registered its beans, so the check always fails and
    // Anthropic silently never loads. We rely on @ConditionalOnProperty
    // alone; if YAYA_LLM_PROVIDER=anthropic but the key is unset the boot
    // fails fast with a clear "no qualifying bean of type ChatModel" — the
    // operator then sets the key or flips provider=stub.

    private static final TypeReference<Map<String, Object>> STRING_MAP = new TypeReference<>() {};

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AnthropicLlmClient.class);

    private final ChatModel chatModel;
    private final ObjectMapper json;

    // Explicit Anthropic prompt caching (cache_control:ephemeral). Default ON.
    // SYSTEM_AND_TOOLS + multiBlockSystemCaching puts the breakpoint on the
    // second-to-last system block — our byte-stable cacheablePrefix — while the
    // variable suffix (the last system block) stays uncached and free to change.
    // Field injection (not constructor) so plain `new` in unit tests leaves this
    // false: tests that stub ChatModel never need a live cache config, and the
    // contract behaviour is identical with or without the hint.
    @Value("${yaya.agentic.llm.anthropic.prompt-cache.enabled:true}")
    private boolean promptCacheEnabled;

    // FIVE_MINUTES (default) or ONE_HOUR. 1h doubles the write premium but
    // survives idle gaps between bursty channel messages (WhatsApp/SMS).
    @Value("${yaya.agentic.llm.anthropic.prompt-cache.ttl:FIVE_MINUTES}")
    private String promptCacheTtl;

    public AnthropicLlmClient(ChatModel chatModel, ObjectMapper json) {
        this.chatModel = chatModel;
        this.json = json;
        LOG.info("AnthropicLlmClient instantiated — backed by ChatModel={}", chatModel.getClass().getSimpleName());
    }

    @Override
    public Flux<LlmEvent> stream(LlmRequest request) {
        Prompt prompt = buildPrompt(request);

        // Spring AI populates ToolCalls as the assistant message materialises;
        // collect them deduped by id and emit at the end.
        LinkedHashMap<String, AssistantMessage.ToolCall> seenCalls = new LinkedHashMap<>();
        // Streamed usage accumulates across chunks; the final chunk carries the
        // settled cache_creation / cache_read counts. Keep the latest non-null
        // so we can log per-round cache effectiveness for cost monitoring.
        AtomicReference<Object> lastUsage = new AtomicReference<>();

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
            Object usage = lastUsage.get();
            if (usage != null) {
                LOG.info("Anthropic round usage (prompt-cache={}): {}", promptCacheEnabled, usage);
            }
            List<LlmEvent> out = new ArrayList<>();
            for (var tc : seenCalls.values()) {
                out.add(new LlmEvent.ToolUseProposal(tc.id(), tc.name(), parseArgs(tc.arguments())));
            }
            // Spring AI doesn't expose the raw Anthropic stop_reason on the
            // streamed response, so we infer it: any captured tool_use means
            // the model paused for tools, otherwise it ended its reply.
            StopReason stop = seenCalls.isEmpty() ? StopReason.END_TURN : StopReason.TOOL_USE;
            out.add(new LlmEvent.Done(stop));
            return out;
        });

        return tokens.concatWith(tail.flatMapMany(Flux::fromIterable));
    }

    // ---- Prompt translation -------------------------------------------

    /*
     * Explicit Anthropic prompt caching (cache_control:ephemeral), wired via
     * Spring AI 1.1's AnthropicCacheOptions. Anthropic has NO automatic content
     * cache — without an explicit breakpoint every request bills the full
     * prefix at input price, which on a multi-round agentic turn means the
     * ~16K-token system + tools are re-billed on every tool round.
     *
     * The cacheablePrefix and variableSuffix are emitted as two separate
     * SystemMessages. With SYSTEM_AND_TOOLS + multiBlockSystemCaching, Spring AI
     * places the cache_control breakpoint on the SECOND-TO-LAST system block
     * (our stable prefix) and on the last tool, while the LAST system block (the
     * per-turn variable suffix) stays uncached. Render order is tools → system →
     * messages, so the breakpoint caches tools + prefix together. The prefix is
     * byte-stable per (tenant, profile, profile_version), so every subsequent
     * round and session on the same profile reads it at ~0.1x.
     */
    private Prompt buildPrompt(LlmRequest request) {
        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
        String prefix = request.cacheablePrefix();
        String suffix = request.variableSuffix();
        if (prefix != null && !prefix.isEmpty()) messages.add(new SystemMessage(prefix));
        if (suffix != null && !suffix.isEmpty()) messages.add(new SystemMessage(suffix));
        messages.addAll(historyToMessages(request.history()));

        AnthropicChatOptions.Builder options = AnthropicChatOptions.builder()
                .toolCallbacks(toCallbacks(request.availableTools()))
                .internalToolExecutionEnabled(false);
        AnthropicCacheOptions cacheOptions = cacheOptions();
        if (cacheOptions != null) options.cacheOptions(cacheOptions);
        return new Prompt(messages, options.build());
    }

    /**
     * The cache configuration for one request, or {@code null} when caching is
     * disabled (the kill-switch leaves the request byte-identical to the
     * pre-caching behaviour). Package-private for unit testing.
     */
    AnthropicCacheOptions cacheOptions() {
        if (!promptCacheEnabled) return null;
        AnthropicCacheTtl ttl = "ONE_HOUR".equalsIgnoreCase(promptCacheTtl)
                ? AnthropicCacheTtl.ONE_HOUR : AnthropicCacheTtl.FIVE_MINUTES;
        return AnthropicCacheOptions.builder()
                .strategy(AnthropicCacheStrategy.SYSTEM_AND_TOOLS)
                .multiBlockSystemCaching(true)
                .messageTypeTtl(org.springframework.ai.chat.messages.MessageType.SYSTEM, ttl)
                .build();
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

    /**
     * Best-effort capture of the streamed usage block. The Anthropic native
     * usage carries {@code cacheCreationInputTokens} / {@code cacheReadInputTokens}
     * — the ground truth for whether the prefix cache is hitting. Never throws:
     * usage logging must not be able to break a chat turn.
     */
    private static void captureUsage(org.springframework.ai.chat.model.ChatResponse response,
                                     AtomicReference<Object> sink) {
        try {
            if (response == null || response.getMetadata() == null) return;
            var usage = response.getMetadata().getUsage();
            if (usage == null) return;
            Object nativeUsage = usage.getNativeUsage();
            sink.set(nativeUsage != null ? nativeUsage : usage);
        } catch (RuntimeException ignored) {
            // Telemetry only — swallow.
        }
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
