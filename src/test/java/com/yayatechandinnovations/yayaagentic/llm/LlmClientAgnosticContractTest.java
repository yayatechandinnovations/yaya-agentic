package com.yayatechandinnovations.yayaagentic.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the engine's agnostic-LLM-SPI claim: the same {@link ChatModel}
 * stream of {@link AssistantMessage}s produces the same
 * {@link LlmClient.LlmEvent} sequence regardless of which provider wrapper
 * (Anthropic vs OpenAI) is on top of it. The engine consumes only the
 * {@link LlmClient.LlmEvent} stream, so this is the contract that keeps
 * the engine unaware of which provider it's talking to.
 * <p>
 * Both wrappers translate Spring AI's response into our event sealed
 * interface using the same code path; what differs is which provider-
 * specific {@code ChatOptions} they build (which only affects what gets
 * sent to the wire, not what the engine sees back). This test ignores
 * the request-side translation and pins the response-side translation
 * shared by both implementations.
 */
class LlmClientAgnosticContractTest {

    private final ObjectMapper json = new ObjectMapper();

    static Stream<Arguments> providers() {
        BiFunction<ChatModel, ObjectMapper, LlmClient> anthropic =
                AnthropicLlmClient::new;
        BiFunction<ChatModel, ObjectMapper, LlmClient> openai =
                OpenAiLlmClient::new;
        return Stream.of(
                Arguments.of("Anthropic", anthropic),
                Arguments.of("OpenAI", openai));
    }

    @ParameterizedTest(name = "{0}: stream of text + tool_call → TokenChunk + ToolUseProposal + Done(TOOL_USE)")
    @MethodSource("providers")
    @DisplayName("Both providers translate the same ChatModel output into the same LlmEvent stream")
    void same_chat_stream_yields_same_event_sequence(
            String name,
            BiFunction<ChatModel, ObjectMapper, LlmClient> factory) {

        ChatModel mock = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException("stream-only mock");
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                // Two response chunks: first a text token, then an
                // assistant message carrying a tool_call. Mirrors what
                // Spring AI surfaces from a real provider when the model
                // wants to call a tool.
                AssistantMessage textOnly = new AssistantMessage("hello ");
                AssistantMessage withTool = new AssistantMessage(
                        "",
                        Map.of(),
                        List.of(new AssistantMessage.ToolCall(
                                "call-xyz",
                                "function",
                                "echo",
                                "{\"text\":\"world\"}")));
                return Flux.just(
                        new ChatResponse(List.of(new Generation(textOnly))),
                        new ChatResponse(List.of(new Generation(withTool))));
            }
        };

        LlmClient client = factory.apply(mock, json);

        LlmClient.LlmRequest request = new LlmClient.LlmRequest(
                "system prefix",
                "session suffix",
                List.of(new LlmClient.HistoryEntry.User("say hello and echo world")),
                List.of(new LlmClient.ToolDefinition(
                        "echo",
                        "echoes a string back",
                        "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}")));

        List<LlmClient.LlmEvent> events = client.stream(request).collectList().block();

        assertThat(events).as("event sequence from %s", name).hasSize(3);
        assertThat(events.get(0))
                .as("first event is the text chunk")
                .isInstanceOfSatisfying(LlmClient.LlmEvent.TokenChunk.class,
                        tc -> assertThat(tc.text()).isEqualTo("hello "));
        assertThat(events.get(1))
                .as("second event is the tool_use proposal")
                .isInstanceOfSatisfying(LlmClient.LlmEvent.ToolUseProposal.class, p -> {
                    assertThat(p.callId()).isEqualTo("call-xyz");
                    assertThat(p.toolName()).isEqualTo("echo");
                    assertThat(p.args()).containsEntry("text", "world");
                });
        assertThat(events.get(2))
                .as("Done carries TOOL_USE because the assistant proposed a tool")
                .isInstanceOfSatisfying(LlmClient.LlmEvent.Done.class,
                        d -> assertThat(d.stopReason())
                                .isEqualTo(LlmClient.StopReason.TOOL_USE));
    }

    @ParameterizedTest(name = "{0}: empty tool-call stream → Done(END_TURN)")
    @MethodSource("providers")
    void stream_without_tool_calls_ends_with_end_turn(
            String name,
            BiFunction<ChatModel, ObjectMapper, LlmClient> factory) {

        ChatModel mock = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException("stream-only mock");
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(new ChatResponse(List.of(
                        new Generation(new AssistantMessage("just a reply, no tools")))));
            }
        };

        LlmClient client = factory.apply(mock, json);
        List<LlmClient.LlmEvent> events = client.stream(new LlmClient.LlmRequest(
                "", "", List.of(new LlmClient.HistoryEntry.User("hi")), List.of()))
                .collectList().block();

        assertThat(events).as("%s without tool calls", name).hasSize(2);
        assertThat(events.get(0))
                .isInstanceOfSatisfying(LlmClient.LlmEvent.TokenChunk.class,
                        tc -> assertThat(tc.text()).isEqualTo("just a reply, no tools"));
        assertThat(events.get(1))
                .isInstanceOfSatisfying(LlmClient.LlmEvent.Done.class,
                        d -> assertThat(d.stopReason())
                                .isEqualTo(LlmClient.StopReason.END_TURN));
    }
}
