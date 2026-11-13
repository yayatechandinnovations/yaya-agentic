package com.yayatechandinnovations.yayaagentic.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.llm.StructuredLlmClient.StructuredLlmException;
import com.yayatechandinnovations.yayaagentic.llm.StructuredLlmClient.StructuredRequest;
import com.yayatechandinnovations.yayaagentic.llm.StructuredLlmClient.StructuredResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.anthropic.api.AnthropicCacheStrategy;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import reactor.core.publisher.Flux;

/**
 * The structured-completion round on Anthropic: one answer tool carrying the
 * caller's schema, forced where the model allows it, the system prompt on the
 * cache breakpoint, and the provider's real token counts handed back.
 */
class AnthropicLlmClientStructuredTest {

    private static final String SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"ids\":{\"type\":\"array\"}},\"required\":[\"ids\"]}";

    private final ChatModel chatModel = mock(ChatModel.class);

    private AnthropicLlmClient client(String model) {
        AnthropicLlmClient client = new AnthropicLlmClient(chatModel, new ObjectMapper());
        ReflectionTestUtils.setField(client, "promptCacheEnabled", true);
        ReflectionTestUtils.setField(client, "promptCacheTtl", "FIVE_MINUTES");
        ReflectionTestUtils.setField(client, "configuredModel", model);
        return client;
    }

    private static StructuredRequest request(String retryHint) {
        return new StructuredRequest("SYSTEM PROMPT", "user text", "emit_result", SCHEMA, 800, retryHint);
    }

    @Test
    void the_request_forces_the_answer_tool_caches_the_system_prompt_and_pins_temperature_zero() {
        Prompt prompt = client("claude-sonnet-4-6").structuredPrompt(request(null));

        // system (cacheable, second-to-last system block) → fixed instruction → the input
        assertThat(prompt.getInstructions()).extracting(m -> m.getMessageType())
                .containsExactly(MessageType.SYSTEM, MessageType.SYSTEM, MessageType.USER);
        assertThat(prompt.getInstructions().get(0).getText()).isEqualTo("SYSTEM PROMPT");
        assertThat(prompt.getInstructions().get(1).getText()).contains("emit_result");
        assertThat(prompt.getInstructions().get(2).getText()).isEqualTo("user text");

        AnthropicChatOptions options = (AnthropicChatOptions) prompt.getOptions();
        assertThat(options.getToolChoice()).isEqualTo(new AnthropicApi.ToolChoiceTool("emit_result", true));
        assertThat(options.getToolCallbacks()).singleElement().satisfies(tool -> {
            assertThat(tool.getToolDefinition().name()).isEqualTo("emit_result");
            assertThat(tool.getToolDefinition().inputSchema()).isEqualTo(SCHEMA);
        });
        assertThat(options.getInternalToolExecutionEnabled()).isFalse();
        assertThat(options.getMaxTokens()).isEqualTo(800);
        assertThat(options.getTemperature()).isEqualTo(0.0);
        assertThat(options.getCacheOptions().getStrategy()).isEqualTo(AnthropicCacheStrategy.SYSTEM_AND_TOOLS);
        assertThat(options.getCacheOptions().isMultiBlockSystemCaching()).isTrue();
    }

    @Test
    void models_that_reject_forced_tool_choice_or_temperature_get_auto_and_no_temperature() {
        for (String model : List.of("claude-opus-5", "claude-opus-5-5", "claude-sonnet-5-5", "claude-fable-5-1",
                "some-future-model")) {
            AnthropicChatOptions options =
                    (AnthropicChatOptions) client(model).structuredPrompt(request(null)).getOptions();

            assertThat(options.getToolChoice()).as(model).isEqualTo(new AnthropicApi.ToolChoiceAuto(true));
            assertThat(options.getTemperature()).as(model).isNull();
        }
        // Opus 4.7/4.8 still take a forced tool, but no sampling parameters.
        AnthropicChatOptions opus48 =
                (AnthropicChatOptions) client("claude-opus-4-8").structuredPrompt(request(null)).getOptions();
        assertThat(opus48.getToolChoice()).isEqualTo(new AnthropicApi.ToolChoiceTool("emit_result", true));
        assertThat(opus48.getTemperature()).isNull();
    }

    @Test
    void a_retry_tells_the_model_what_was_wrong_without_touching_the_cached_system_block() {
        Prompt prompt = client("claude-sonnet-4-6").structuredPrompt(request("$.ids: required"));

        assertThat(prompt.getInstructions().get(0).getText()).isEqualTo("SYSTEM PROMPT");
        assertThat(prompt.getInstructions().get(1).getText()).contains("$.ids: required");
    }

    @Test
    void the_tool_call_arguments_are_the_output_and_usage_is_the_providers_real_count() {
        // Spring AI splits Anthropic's text and tool_use blocks into separate generations.
        Generation text = new Generation(new AssistantMessage("thinking out loud"));
        Generation tool = new Generation(
                AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall(
                        "toolu_1", "function", "emit_result", "{\"ids\":[\"a\",\"b\"]}"))).build(),
                ChatGenerationMetadata.builder().finishReason("tool_use").build());
        // …and the answer arrives as a stream: the tool call and the settled usage in a later chunk.
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(text)),
                new ChatResponse(List.of(tool),
                        ChatResponseMetadata.builder()
                                .model("claude-sonnet-4-6")
                                .usage(new DefaultUsage(34, 210, 244, new AnthropicApi.Usage(34, 210, 200, 1000)))
                                .build())));

        StructuredResult result = client("claude-sonnet-4-6").complete(request(null));

        assertThat(result.output()).containsEntry("ids", List.of("a", "b"));
        assertThat(result.truncated()).isFalse();
        assertThat(result.inputTokens()).as("uncached + cache write + cache read").isEqualTo(1234);
        assertThat(result.outputTokens()).isEqualTo(210);
        assertThat(result.cacheReadTokens()).isEqualTo(1000);
        assertThat(result.cacheCreationTokens()).isEqualTo(200);
        assertThat(result.model()).isEqualTo("claude-sonnet-4-6");

        ArgumentCaptor<Prompt> sent = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).stream(sent.capture());
        assertThat(sent.getValue().getInstructions()).hasSize(3);
    }

    @Test
    void an_answer_cut_off_at_the_token_cap_has_no_output_and_is_flagged_truncated() {
        Generation cut = new Generation(
                AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall(
                        "toolu_1", "function", "emit_result", "{\"ids\":[\"a\","))).build(),
                ChatGenerationMetadata.builder().finishReason("max_tokens").build());
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(cut))));

        StructuredResult result = client("claude-sonnet-4-6").complete(request(null));

        assertThat(result.output()).isNull();
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void a_provider_error_becomes_a_structured_llm_exception_without_the_provider_message() {
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.error(new IllegalStateException("400 … request body echoed: SYSTEM PROMPT")));

        assertThatThrownBy(() -> client("claude-sonnet-4-6").complete(request(null)))
                .isInstanceOf(StructuredLlmException.class)
                .hasMessageNotContaining("SYSTEM PROMPT");
    }
}
