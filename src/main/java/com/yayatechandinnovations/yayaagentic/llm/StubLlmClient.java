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
public class StubLlmClient implements LlmClient, StructuredLlmClient {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(StubLlmClient.class);

    public StubLlmClient() {
        LOG.info("StubLlmClient instantiated — engine will use the pattern-matching stub "
                + "unless AnthropicLlmClient also loads (it's @Primary).");
    }

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
                    .concatWith(Mono.just(new LlmEvent.Done(StopReason.END_TURN)));
        }

        String userMessage = lastUserMessage(history);
        String trimmed = userMessage.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);

        boolean echoAvailable = request.availableTools() != null
                && request.availableTools().stream().anyMatch(t -> "echo".equals(t.name()));

        if (echoAvailable && (lower.equals("/echo") || lower.equals("echo"))) {
            return Flux.just(
                    (LlmEvent) new LlmEvent.ToolUseProposal(callId(), "echo", Map.of()),
                    new LlmEvent.Done(StopReason.TOOL_USE));
        }
        if (echoAvailable && (lower.startsWith("/echo ") || lower.startsWith("echo "))) {
            String arg = trimmed.substring(lower.startsWith("/") ? 6 : 5).trim();
            return Flux.just(
                    (LlmEvent) new LlmEvent.ToolUseProposal(callId(), "echo", Map.of("text", arg)),
                    new LlmEvent.Done(StopReason.TOOL_USE));
        }

        return Flux.fromIterable(scriptFor(trimmed))
                .delayElements(Duration.ofMillis(40))
                .<LlmEvent>map(LlmEvent.TokenChunk::new)
                .concatWith(Mono.just(new LlmEvent.Done(StopReason.END_TURN)));
    }

    // ---- Structured completion (offline, deterministic) ---------------

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** Test hook: when set, decides the answer instead of the schema skeleton. */
    private volatile java.util.function.Function<StructuredRequest, Map<String, Object>> structuredResponder;

    /** Test hook — pass {@code null} to restore the default skeleton answer. */
    public void setStructuredResponder(
            java.util.function.Function<StructuredRequest, Map<String, Object>> responder) {
        this.structuredResponder = responder;
    }

    @Override
    public String provider() { return "stub"; }

    /**
     * No model: answers with the smallest object the schema's {@code required}
     * list allows (empty strings/arrays, zeroes, first enum value), or whatever
     * the test hook returns. Token counts are a deterministic chars/4 estimate
     * so callers can exercise their usage plumbing offline.
     */
    @Override
    public StructuredResult complete(StructuredRequest request) {
        var responder = structuredResponder;
        Map<String, Object> output;
        try {
            output = responder != null
                    ? responder.apply(request)
                    : JSON.convertValue(skeleton(JSON.readTree(request.schemaJson())),
                            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new StructuredLlmException("stub could not read the schema", e);
        }
        long in = ((request.system() == null ? 0 : request.system().length())
                + request.input().length() + request.schemaJson().length()) / 4;
        long out = output == null ? 0 : JSON.valueToTree(output).toString().length() / 4;
        return new StructuredResult(output, false, in, out, 0, 0, "stub");
    }

    private static com.fasterxml.jackson.databind.JsonNode skeleton(com.fasterxml.jackson.databind.JsonNode schema) {
        var f = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        if (schema == null || !schema.isObject()) return f.nullNode();
        if (schema.has("const")) return schema.get("const");
        if (schema.path("enum").isArray() && !schema.get("enum").isEmpty()) return schema.get("enum").get(0);
        var type = schema.path("type");
        String t = type.isArray() && !type.isEmpty() ? type.get(0).asText() : type.asText("");
        return switch (t) {
            case "object" -> {
                var obj = f.objectNode();
                for (var name : schema.path("required")) {
                    obj.set(name.asText(), skeleton(schema.path("properties").get(name.asText())));
                }
                yield obj;
            }
            case "array" -> f.arrayNode();
            case "string" -> f.textNode("");
            case "integer", "number" -> schema.has("minimum") ? schema.get("minimum") : f.numberNode(0);
            case "boolean" -> f.booleanNode(false);
            default -> f.nullNode();
        };
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
