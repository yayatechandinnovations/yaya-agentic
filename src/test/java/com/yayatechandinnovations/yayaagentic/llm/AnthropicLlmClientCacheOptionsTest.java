package com.yayatechandinnovations.yayaagentic.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.api.AnthropicCacheOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheStrategy;
import org.springframework.ai.anthropic.api.AnthropicCacheTtl;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Locks in the explicit prompt-caching wiring (design: cost control). The
 * SYSTEM_AND_TOOLS + multiBlockSystemCaching combination is load-bearing — it
 * is what places the cache_control breakpoint on the byte-stable
 * cacheablePrefix (the second-to-last system block) instead of the per-turn
 * variable suffix. If this regresses, every agentic round silently re-bills the
 * full ~16K-token prefix at input price.
 */
class AnthropicLlmClientCacheOptionsTest {

    private AnthropicLlmClient client() {
        // The constructor only calls chatModel.getClass().getSimpleName() for a
        // log line; a plain mock satisfies that. cacheOptions() never touches it.
        return new AnthropicLlmClient(mock(ChatModel.class), new ObjectMapper());
    }

    @Test
    void enabled_caches_system_and_tools_on_the_stable_prefix() {
        AnthropicLlmClient client = client();
        ReflectionTestUtils.setField(client, "promptCacheEnabled", true);
        ReflectionTestUtils.setField(client, "promptCacheTtl", "FIVE_MINUTES");

        AnthropicCacheOptions opts = client.cacheOptions();

        assertThat(opts).isNotNull();
        assertThat(opts.getStrategy()).isEqualTo(AnthropicCacheStrategy.SYSTEM_AND_TOOLS);
        assertThat(opts.isMultiBlockSystemCaching())
                .as("breakpoint must land on the stable prefix block, not the variable suffix")
                .isTrue();
        assertThat(opts.getMessageTypeTtl().get(MessageType.SYSTEM)).isEqualTo(AnthropicCacheTtl.FIVE_MINUTES);
    }

    @Test
    void one_hour_ttl_is_honoured() {
        AnthropicLlmClient client = client();
        ReflectionTestUtils.setField(client, "promptCacheEnabled", true);
        ReflectionTestUtils.setField(client, "promptCacheTtl", "ONE_HOUR");

        assertThat(client.cacheOptions().getMessageTypeTtl().get(MessageType.SYSTEM))
                .isEqualTo(AnthropicCacheTtl.ONE_HOUR);
    }

    @Test
    void disabled_is_the_kill_switch() {
        AnthropicLlmClient client = client();
        ReflectionTestUtils.setField(client, "promptCacheEnabled", false);

        assertThat(client.cacheOptions())
                .as("caching off must leave the request byte-identical to pre-caching behaviour")
                .isNull();
    }
}
