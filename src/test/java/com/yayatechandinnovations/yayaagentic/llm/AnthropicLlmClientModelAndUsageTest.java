package com.yayatechandinnovations.yayaagentic.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** A profile's model reaches the provider request, and a round's usage is read back whole. */
class AnthropicLlmClientModelAndUsageTest {

    private final AnthropicLlmClient client = new AnthropicLlmClient(mock(ChatModel.class), new ObjectMapper());

    @Test
    void a_request_model_overrides_the_deployment_model_for_that_round_only() {
        Prompt pinned = client.buildPrompt(new LlmClient.LlmRequest("prefix", "suffix",
                List.of(new LlmClient.HistoryEntry.User("hola")), List.of(), "the-profile-model"));
        assertThat(((AnthropicChatOptions) pinned.getOptions()).getModel()).isEqualTo("the-profile-model");

        Prompt unpinned = client.buildPrompt(new LlmClient.LlmRequest("prefix", "suffix",
                List.of(new LlmClient.HistoryEntry.User("hola")), List.of()));
        assertThat(((AnthropicChatOptions) unpinned.getOptions()).getModel()).isNull();
    }

    @Test
    void input_tokens_include_cached_reads_and_cache_writes() {
        LlmClient.Usage usage = AnthropicLlmClient.toUsage(new AnthropicApi.Usage(10, 5, 100, 1000));
        assertThat(usage).isEqualTo(new LlmClient.Usage(1110, 5, 1000, 100));
        assertThat(usage.cacheReadTokens()).as("read from the cache").isEqualTo(1000);
        assertThat(usage.cacheCreationTokens()).as("written to the cache").isEqualTo(100);
    }

    @Test
    void missing_or_unknown_usage_is_reported_as_absent_not_as_zero() {
        assertThat(AnthropicLlmClient.toUsage(null)).isNull();
        assertThat(AnthropicLlmClient.toUsage("something else")).isNull();
    }
}
