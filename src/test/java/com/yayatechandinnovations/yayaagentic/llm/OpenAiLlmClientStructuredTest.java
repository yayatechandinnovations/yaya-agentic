package com.yayatechandinnovations.yayaagentic.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.yayatechandinnovations.yayaagentic.llm.StructuredLlmClient.StructuredLlmException;
import com.yayatechandinnovations.yayaagentic.llm.StructuredLlmClient.StructuredRequest;
import com.yayatechandinnovations.yayaagentic.llm.StructuredLlmClient.StructuredResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The structured-completion round on OpenAI: one answer tool carrying the
 * caller's schema, a forced {@code tool_choice} on it, the arguments handed
 * back as the output and the provider's real token counts read whole.
 */
class OpenAiLlmClientStructuredTest {

    private static final String SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"ids\":{\"type\":\"array\"}},\"required\":[\"ids\"]}";

    private static final Object FORCED_EMIT_RESULT =
            OpenAiApi.ChatCompletionRequest.ToolChoiceBuilder.function("emit_result");

    private static StructuredRequest request(String retryHint) {
        return new StructuredRequest("SYSTEM PROMPT", "user text", "emit_result", SCHEMA, 800, retryHint);
    }

    private static StructuredRequest pinned(String model) {
        return new StructuredRequest("SYSTEM PROMPT", "user text", "emit_result", SCHEMA, 800, null, model);
    }

    private static OpenAiLlmClient client(ChatModel chatModel, String configuredModel) {
        OpenAiLlmClient client = new OpenAiLlmClient(chatModel, new ObjectMapper());
        ReflectionTestUtils.setField(client, "configuredModel", configuredModel);
        return client;
    }

    @Nested
    class OnTheChatModel {

        private final ChatModel chatModel = mock(ChatModel.class);
        private final OpenAiLlmClient client = client(chatModel, "gpt-4o-mini");

        @Test
        void the_provider_is_openai() {
            assertThat(client.provider()).isEqualTo("openai");
        }

        @Test
        void the_request_carries_exactly_one_tool_with_the_schema_and_forces_it() {
            Prompt prompt = client.structuredPrompt(request(null));

            assertThat(prompt.getInstructions()).extracting(m -> m.getMessageType())
                    .containsExactly(MessageType.SYSTEM, MessageType.SYSTEM, MessageType.USER);
            assertThat(prompt.getInstructions().get(0).getText()).isEqualTo("SYSTEM PROMPT");
            assertThat(prompt.getInstructions().get(1).getText()).contains("emit_result");
            assertThat(prompt.getInstructions().get(2).getText()).isEqualTo("user text");

            OpenAiChatOptions options = (OpenAiChatOptions) prompt.getOptions();
            assertThat(options.getToolCallbacks()).singleElement().satisfies(tool -> {
                assertThat(tool.getToolDefinition().name()).isEqualTo("emit_result");
                assertThat(tool.getToolDefinition().inputSchema()).isEqualTo(SCHEMA);
            });
            assertThat(options.getToolChoice()).isEqualTo(FORCED_EMIT_RESULT);
            assertThat(options.getInternalToolExecutionEnabled()).isFalse();
            assertThat(options.getMaxCompletionTokens()).isEqualTo(800);
            assertThat(options.getModel()).as("unpinned: the deployment's model").isNull();
            assertThat(options.getTemperature()).isNull();
        }

        @Test
        void a_pinned_model_reaches_the_request_for_that_round_only() {
            OpenAiChatOptions options = (OpenAiChatOptions) client.structuredPrompt(pinned("gpt-4.1")).getOptions();
            assertThat(options.getModel()).isEqualTo("gpt-4.1");
        }

        @Test
        void a_retry_tells_the_model_what_was_wrong_without_touching_the_system_block() {
            Prompt prompt = client.structuredPrompt(request("$.ids: required"));

            assertThat(prompt.getInstructions().get(0).getText()).isEqualTo("SYSTEM PROMPT");
            assertThat(prompt.getInstructions().get(1).getText()).contains("$.ids: required");
        }

        @Test
        void the_tool_call_arguments_are_the_output_and_usage_is_the_providers_real_count() {
            Generation tool = new Generation(
                    AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall(
                            "call_1", "function", "emit_result", "{\"ids\":[\"a\",\"b\"]}"))).build(),
                    ChatGenerationMetadata.builder().finishReason("TOOL_CALLS").build());
            // Streaming: the settled usage arrives in a trailing chunk with no choices.
            OpenAiApi.Usage usage = new OpenAiApi.Usage(210, 1234, 1444,
                    new OpenAiApi.Usage.PromptTokensDetails(0, 1000), null);
            when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                    new ChatResponse(List.of(tool), ChatResponseMetadata.builder().model("gpt-4o-mini-2024-07-18").build()),
                    new ChatResponse(List.of(),
                            ChatResponseMetadata.builder()
                                    .model("gpt-4o-mini-2024-07-18")
                                    .usage(new DefaultUsage(1234, 210, 1444, usage))
                                    .build())));

            StructuredResult result = client.complete(request(null));

            assertThat(result.output()).containsEntry("ids", List.of("a", "b"));
            assertThat(result.truncated()).isFalse();
            assertThat(result.inputTokens()).as("prompt_tokens already includes the cached ones").isEqualTo(1234);
            assertThat(result.outputTokens()).isEqualTo(210);
            assertThat(result.cacheReadTokens()).isEqualTo(1000);
            assertThat(result.cacheCreationTokens()).isZero();
            assertThat(result.model()).isEqualTo("gpt-4o-mini-2024-07-18");
        }

        @Test
        void without_a_reported_model_the_configured_one_is_returned() {
            when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(
                    AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall(
                            "call_1", "function", "emit_result", "{\"ids\":[]}"))).build())))));

            StructuredResult result = client.complete(request(null));

            assertThat(result.output()).containsEntry("ids", List.of());
            assertThat(result.model()).isEqualTo("gpt-4o-mini");
            assertThat(result.inputTokens()).isZero();
        }

        @Test
        void an_answer_cut_off_at_the_token_cap_has_no_output_and_is_flagged_truncated() {
            Generation cut = new Generation(
                    AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall(
                            "call_1", "function", "emit_result", "{\"ids\":[\"a\","))).build(),
                    ChatGenerationMetadata.builder().finishReason("LENGTH").build());
            when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(cut))));

            StructuredResult result = client.complete(request(null));

            assertThat(result.output()).isNull();
            assertThat(result.truncated()).isTrue();
        }

        @Test
        void a_call_for_another_tool_is_no_answer() {
            when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(
                    AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall(
                            "call_1", "function", "something_else", "{\"ids\":[]}"))).build())))));

            assertThat(client.complete(request(null)).output()).isNull();
        }

        @Test
        void a_provider_error_becomes_a_structured_llm_exception_without_the_provider_message() {
            when(chatModel.stream(any(Prompt.class)))
                    .thenReturn(Flux.error(new IllegalStateException("400 … request body echoed: SYSTEM PROMPT")));

            assertThatThrownBy(() -> client.complete(request(null)))
                    .isInstanceOf(StructuredLlmException.class)
                    .hasMessageNotContaining("SYSTEM PROMPT");
        }

        @Test
        void an_empty_stream_is_a_provider_failure() {
            when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.empty());

            assertThatThrownBy(() -> client.complete(request(null))).isInstanceOf(StructuredLlmException.class);
        }
    }

    /**
     * The round as it goes on the wire, through the real Spring AI
     * {@code OpenAiChatModel} against a fake chat-completions endpoint.
     */
    @Nested
    class OnTheWire {

        private static final ObjectMapper JSON = new ObjectMapper();

        private final WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());

        private OpenAiLlmClient wireClient() {
            server.start();
            OpenAiChatModel model = OpenAiChatModel.builder()
                    .openAiApi(OpenAiApi.builder().baseUrl(server.baseUrl()).apiKey("not-a-real-key").build())
                    .defaultOptions(OpenAiChatOptions.builder().model("gpt-4o-mini").build())
                    .build();
            return client(model, "gpt-4o-mini");
        }

        @AfterEach
        void stop() {
            server.stop();
        }

        private static String chunk(String json) {
            return "data: " + json + "\n\n";
        }

        private static final String TOOL_CALL_CHUNK = chunk("{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\","
                + "\"created\":1700000000,\"model\":\"gpt-4o-mini-2024-07-18\",\"choices\":[{\"index\":0,\"delta\":{"
                + "\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"emit_result\",\"arguments\":\"\"}}]},\"finish_reason\":null}]}");
        private static final String ARGS_CHUNK_1 = chunk("{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\","
                + "\"created\":1700000000,\"model\":\"gpt-4o-mini-2024-07-18\",\"choices\":[{\"index\":0,\"delta\":{"
                + "\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"ids\\\":[\\\"a\\\",\"}}]},\"finish_reason\":null}]}");
        private static final String ARGS_CHUNK_2 = chunk("{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\","
                + "\"created\":1700000000,\"model\":\"gpt-4o-mini-2024-07-18\",\"choices\":[{\"index\":0,\"delta\":{"
                + "\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"b\\\"]}\"}}]},\"finish_reason\":null}]}");
        private static final String DONE_CHUNK = chunk("{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\","
                + "\"created\":1700000000,\"model\":\"gpt-4o-mini-2024-07-18\",\"choices\":[{\"index\":0,\"delta\":{},"
                + "\"finish_reason\":\"tool_calls\"}]}");
        private static final String USAGE_CHUNK = chunk("{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\","
                + "\"created\":1700000000,\"model\":\"gpt-4o-mini-2024-07-18\",\"choices\":[],"
                + "\"usage\":{\"prompt_tokens\":1234,\"completion_tokens\":21,\"total_tokens\":1255,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":1024,\"audio_tokens\":0}}}");

        @Test
        void one_forced_tool_goes_out_and_its_streamed_arguments_come_back_as_the_output() throws Exception {
            OpenAiLlmClient client = wireClient();
            server.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(200)
                    .withHeader("Content-Type", "text/event-stream")
                    .withBody(TOOL_CALL_CHUNK + ARGS_CHUNK_1 + ARGS_CHUNK_2 + DONE_CHUNK + USAGE_CHUNK + "data: [DONE]\n\n")));

            StructuredResult result = client.complete(pinned("gpt-4.1"));

            assertThat(result.output()).containsEntry("ids", List.of("a", "b"));
            assertThat(result.truncated()).isFalse();
            assertThat(result.inputTokens()).isEqualTo(1234);
            assertThat(result.outputTokens()).isEqualTo(21);
            assertThat(result.cacheReadTokens()).isEqualTo(1024);
            assertThat(result.model()).isEqualTo("gpt-4o-mini-2024-07-18");

            var requests = server.findAll(com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
                    urlEqualTo("/v1/chat/completions")));
            assertThat(requests).hasSize(1);
            JsonNode body = JSON.readTree(requests.get(0).getBody());
            assertThat(body.get("model").asText()).as("the pinned model wins over the deployment's").isEqualTo("gpt-4.1");
            assertThat(body.get("stream").asBoolean()).isTrue();
            assertThat(body.get("max_completion_tokens").asInt()).isEqualTo(800);
            assertThat(body.has("max_tokens")).isFalse();
            assertThat(body.get("tools")).hasSize(1);
            JsonNode function = body.get("tools").get(0).get("function");
            assertThat(function.get("name").asText()).isEqualTo("emit_result");
            assertThat(function.get("parameters")).isEqualTo(JSON.readTree(SCHEMA));
            assertThat(body.get("tool_choice")).isEqualTo(JSON.readTree(
                    "{\"type\":\"function\",\"function\":{\"name\":\"emit_result\"}}"));
            assertThat(body.get("messages")).extracting(m -> m.get("role").asText())
                    .containsExactly("system", "system", "user");
            assertThat(body.get("messages").get(2).get("content").asText()).isEqualTo("user text");
        }

        @Test
        void a_length_stop_is_truncated() {
            OpenAiLlmClient client = wireClient();
            String cut = chunk("{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,"
                    + "\"model\":\"gpt-4o-mini-2024-07-18\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"length\"}]}");
            server.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(200)
                    .withHeader("Content-Type", "text/event-stream")
                    .withBody(TOOL_CALL_CHUNK + ARGS_CHUNK_1 + cut + "data: [DONE]\n\n")));

            StructuredResult result = client.complete(request(null));

            assertThat(result.truncated()).isTrue();
            assertThat(result.output()).isNull();
        }

        @Test
        void a_500_from_the_provider_is_a_structured_llm_exception() {
            OpenAiLlmClient client = wireClient();
            server.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(500)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"error\":{\"message\":\"server exploded while reading SYSTEM PROMPT\"}}"
                            .getBytes(StandardCharsets.UTF_8))));

            assertThatThrownBy(() -> client.complete(request(null)))
                    .isInstanceOf(StructuredLlmException.class)
                    .hasMessageNotContaining("SYSTEM PROMPT");
        }
    }
}
