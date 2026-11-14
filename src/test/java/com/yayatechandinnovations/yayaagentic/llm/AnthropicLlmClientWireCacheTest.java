package com.yayatechandinnovations.yayaagentic.llm;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The request as it goes on the wire to Anthropic, built by the real Spring AI
 * {@code AnthropicChatModel} (no network): where the {@code cache_control}
 * breakpoints land and what sits before and after them.
 *
 * <p>Cost depends on this. Anthropic caches by exact prefix in the order
 * tools → system → messages, so anything that differs per session or per turn
 * must come AFTER the breakpoint that closes the stable part, and that stable
 * part must be byte-identical for every session of one profile version.
 */
class AnthropicLlmClientWireCacheTest {

    private static final ObjectMapper JSON = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private static final List<LlmClient.ToolDefinition> TOOLS = List.of(
            new LlmClient.ToolDefinition("report_sales", "Sales reports.",
                    "{\"type\":\"object\",\"properties\":{\"date\":{\"type\":\"string\"}}}"),
            new LlmClient.ToolDefinition("resolve", "Find a record.",
                    "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}"));

    private static final String PREFIX = "Role:\nOwner assistant\n\nCapabilities (what the user can ask for):\n- reports\n";

    private final AnthropicChatModel model = AnthropicChatModel.builder()
            .anthropicApi(AnthropicApi.builder().apiKey("not-a-real-key").build())
            .defaultOptions(AnthropicChatOptions.builder().model("claude-sonnet-4-6").maxTokens(1024).build())
            .build();

    private AnthropicLlmClient client(boolean cacheEnabled, String ttl) {
        AnthropicLlmClient client = new AnthropicLlmClient(model, new ObjectMapper());
        ReflectionTestUtils.setField(client, "promptCacheEnabled", cacheEnabled);
        ReflectionTestUtils.setField(client, "promptCacheTtl", ttl);
        return client;
    }

    @Test
    void two_sessions_of_one_profile_send_an_identical_cached_prefix_and_everything_volatile_after_it() throws Exception {
        AnthropicLlmClient client = client(true, "FIVE_MINUTES");
        String aliceSuffix = "Session: channel=app principal=alice\n\nFecha y hora actual: lunes 9 de noviembre de 2026, 09:15"
                + "\n\n<client_ui_context trust=\"untrusted\">\nscreen: tickets\n</client_ui_context>";
        String bobSuffix = "Session: channel=whatsapp principal=bob\n\nFecha y hora actual: viernes 13 de noviembre de 2026, 18:40"
                + "\n\nWorking memory: {\"station\":\"014\"}";

        JsonNode alice = wire(client, new LlmClient.LlmRequest(PREFIX, aliceSuffix,
                List.of(new LlmClient.HistoryEntry.User("ventas de hoy")), TOOLS));
        JsonNode bob = wire(client, new LlmClient.LlmRequest(PREFIX, bobSuffix,
                List.of(new LlmClient.HistoryEntry.User("¿quién ganó ayer?")), TOOLS));

        // The stable part — every tool definition and the first system block —
        // is the same bytes for both sessions, breakpoint included.
        String aliceStable = JSON.writeValueAsString(alice.get("tools")) + JSON.writeValueAsString(alice.get("system").get(0));
        String bobStable = JSON.writeValueAsString(bob.get("tools")) + JSON.writeValueAsString(bob.get("system").get(0));
        assertThat(aliceStable).isEqualTo(bobStable);
        assertThat(alice.get("system").get(0).get("text").asText()).isEqualTo(PREFIX);
        assertThat(alice.get("system").get(0).has("cache_control"))
                .as("the stable system block closes the cached prefix").isTrue();
        assertThat(lastOf(alice.get("tools")).has("cache_control"))
                .as("the tool definitions are cached too").isTrue();

        // Nothing per-session or per-turn is inside it…
        assertThat(aliceStable).doesNotContain("alice", "09:15", "client_ui_context", "ventas de hoy");
        assertThat(bobStable).doesNotContain("bob", "18:40", "Working memory", "ganó");

        // …it all sits in the system block AFTER the breakpoint, which is never
        // a breakpoint itself, and in the conversation.
        assertThat(alice.get("system")).hasSize(2);
        assertThat(alice.get("system").get(1).get("text").asText()).isEqualTo(aliceSuffix);
        assertThat(bob.get("system").get(1).get("text").asText()).isEqualTo(bobSuffix);
        assertThat(alice.get("system").get(1).has("cache_control")).isFalse();
        assertThat(bob.get("system").get(1).has("cache_control")).isFalse();
    }

    @Test
    void the_conversation_breakpoint_is_always_the_last_block_of_the_last_message() {
        AnthropicLlmClient client = client(true, "FIVE_MINUTES");
        for (int rounds = 0; rounds <= 11; rounds++) {
            for (int parallel = 1; parallel <= 3; parallel++) {
                JsonNode wire = wire(client, new LlmClient.LlmRequest(PREFIX, "suffix",
                        turn(rounds, parallel), TOOLS));

                List<JsonNode> marked = markedMessageBlocks(wire);
                JsonNode lastMessage = lastOf(wire.get("messages"));
                assertThat(marked)
                        .as("rounds=%d parallel=%d: exactly one conversation breakpoint", rounds, parallel)
                        .hasSize(1);
                assertThat(marked.get(0))
                        .as("rounds=%d parallel=%d: on the newest block, so the next round reads all of it",
                                rounds, parallel)
                        .isSameAs(lastOf(lastMessage.get("content")));
                assertThat(wire.get("system").get(0).has("cache_control"))
                        .as("the conversation breakpoint never takes the stable prefix's place").isTrue();
                assertThat(breakpoints(wire)).as("Anthropic allows four").isLessThanOrEqualTo(4);
            }
        }
    }

    @Test
    void an_earlier_identical_tool_result_does_not_take_the_breakpoint() {
        AnthropicLlmClient client = client(true, "FIVE_MINUTES");
        List<LlmClient.HistoryEntry> history = new ArrayList<>();
        history.add(new LlmClient.HistoryEntry.User("apaga las ventas de la 014 y la 015"));
        for (int round = 0; round < 4; round++) {
            history.add(new LlmClient.HistoryEntry.Assistant("", List.of(
                    new LlmClient.ToolCallSpec("call-" + round, "resolve", Map.of("q", "0" + round)))));
            history.add(new LlmClient.HistoryEntry.ToolResults(List.of(
                    new LlmClient.ToolResultSpec("call-" + round, Map.of("ok", true), false))));
        }

        JsonNode wire = wire(client, new LlmClient.LlmRequest(PREFIX, "suffix", history, TOOLS));

        List<JsonNode> marked = markedMessageBlocks(wire);
        assertThat(marked).hasSize(1);
        assertThat(marked.get(0).get("tool_use_id").asText()).isEqualTo("call-3");
    }

    @Test
    void the_next_round_of_a_turn_extends_the_previous_request_byte_for_byte() throws Exception {
        // What makes the conversation breakpoint pay off: round 2 is round 1
        // plus new blocks at the end, so everything up to round 1's breakpoint
        // is read from the cache.
        AnthropicLlmClient client = client(true, "FIVE_MINUTES");
        String suffix = "Session: channel=app principal=alice\n\nFecha y hora actual: lunes 9 de noviembre de 2026, 09:15";

        JsonNode first = withoutBreakpoints(wire(client, new LlmClient.LlmRequest(PREFIX, suffix, turn(1, 2), TOOLS)));
        JsonNode second = withoutBreakpoints(wire(client, new LlmClient.LlmRequest(PREFIX, suffix, turn(2, 2), TOOLS)));

        assertThat(second.get("tools")).isEqualTo(first.get("tools"));
        assertThat(second.get("system")).isEqualTo(first.get("system"));
        assertThat(second.get("messages").size()).isGreaterThan(first.get("messages").size());
        for (int i = 0; i < first.get("messages").size(); i++) {
            assertThat(JSON.writeValueAsString(second.get("messages").get(i)))
                    .isEqualTo(JSON.writeValueAsString(first.get("messages").get(i)));
        }
    }

    @Test
    void a_one_hour_prefix_never_follows_a_shorter_lived_breakpoint() {
        // Anthropic rejects a request where a longer TTL comes after a shorter
        // one (order: tools, system, messages).
        JsonNode wire = wire(client(true, "ONE_HOUR"), new LlmClient.LlmRequest(PREFIX, "suffix", turn(2, 1), TOOLS));

        assertThat(lastOf(wire.get("tools")).get("cache_control").get("ttl").asText()).isEqualTo("1h");
        assertThat(wire.get("system").get(0).get("cache_control").get("ttl").asText()).isEqualTo("1h");
        assertThat(markedMessageBlocks(wire).get(0).get("cache_control").get("ttl").asText()).isEqualTo("5m");
    }

    @Test
    void the_kill_switch_sends_no_breakpoint_at_all() {
        JsonNode wire = wire(client(false, "FIVE_MINUTES"),
                new LlmClient.LlmRequest(PREFIX, "suffix", turn(2, 2), TOOLS));

        assertThat(breakpoints(wire)).isZero();
    }

    // ---- helpers ----------------------------------------------------------

    /** One user message followed by {@code rounds} tool rounds of {@code parallel} calls each. */
    private static List<LlmClient.HistoryEntry> turn(int rounds, int parallel) {
        List<LlmClient.HistoryEntry> history = new ArrayList<>();
        history.add(new LlmClient.HistoryEntry.User("¿cuánto se vendió hoy?"));
        for (int round = 0; round < rounds; round++) {
            List<LlmClient.ToolCallSpec> calls = new ArrayList<>();
            List<LlmClient.ToolResultSpec> results = new ArrayList<>();
            for (int p = 0; p < parallel; p++) {
                String id = "call-" + round + "-" + p;
                calls.add(new LlmClient.ToolCallSpec(id, "report_sales", Map.of("date", "2026-11-0" + (p + 1))));
                results.add(new LlmClient.ToolResultSpec(id, Map.of("total", 1000 * round + p), false));
            }
            history.add(new LlmClient.HistoryEntry.Assistant(round % 2 == 0 ? "Déjame ver." : "", calls));
            history.add(new LlmClient.HistoryEntry.ToolResults(results));
        }
        return history;
    }

    /** The request body Spring AI would send for this round. */
    private JsonNode wire(AnthropicLlmClient client, LlmClient.LlmRequest request) {
        Prompt prompt = client.buildPrompt(request);
        Prompt merged = ReflectionTestUtils.invokeMethod(model, "buildRequestPrompt", prompt);
        Object body = ReflectionTestUtils.invokeMethod(model, "createRequest", merged, true);
        return JSON.valueToTree(body);
    }

    private static JsonNode lastOf(JsonNode array) {
        return array.get(array.size() - 1);
    }

    private static List<JsonNode> markedMessageBlocks(JsonNode wire) {
        List<JsonNode> marked = new ArrayList<>();
        for (JsonNode message : wire.get("messages")) {
            for (JsonNode block : message.get("content")) {
                if (block.has("cache_control")) marked.add(block);
            }
        }
        return marked;
    }

    private static int breakpoints(JsonNode wire) {
        int count = markedMessageBlocks(wire).size();
        if (wire.has("tools")) for (JsonNode tool : wire.get("tools")) if (tool.has("cache_control")) count++;
        if (wire.has("system") && wire.get("system").isArray()) {
            for (JsonNode block : wire.get("system")) if (block.has("cache_control")) count++;
        }
        return count;
    }

    private static JsonNode withoutBreakpoints(JsonNode wire) {
        JsonNode copy = wire.deepCopy();
        copy.findParents("cache_control").forEach(parent -> ((ObjectNode) parent).remove("cache_control"));
        return copy;
    }
}
