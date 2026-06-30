package com.yayatechandinnovations.yayaagentic.engine;

import com.yayatechandinnovations.yayaagentic.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The denied-tool retry guard: a tool call that already came back as an error
 * this turn must be recognised so the engine refuses to re-dispatch it (which
 * otherwise spins the agentic loop and re-emits the same prose — the WhatsApp
 * "3x message" bug). Tests the signature derivation directly.
 */
class DefaultConversationEngineToolGuardTest {

    private static LlmClient.HistoryEntry.Assistant call(String callId, String tool, Map<String, Object> args) {
        return new LlmClient.HistoryEntry.Assistant(
                "", List.of(new LlmClient.ToolCallSpec(callId, tool, args)));
    }

    private static LlmClient.HistoryEntry.ToolResults result(String callId, boolean isError) {
        return new LlmClient.HistoryEntry.ToolResults(
                List.of(new LlmClient.ToolResultSpec(callId, "x", isError)));
    }

    @Test
    void a_failed_call_is_in_the_failed_signature_set() {
        var history = List.<LlmClient.HistoryEntry>of(
                call("c1", "ticket_draft_ensure", Map.of()),
                result("c1", /* isError */ true));

        assertThat(DefaultConversationEngine.failedToolSignatures(history))
                .contains(DefaultConversationEngine.toolSignature("ticket_draft_ensure", Map.of()));
    }

    @Test
    void a_successful_call_is_not_guarded() {
        var history = List.<LlmClient.HistoryEntry>of(
                call("c1", "ticket_draft_ensure", Map.of()),
                result("c1", /* isError */ false));

        assertThat(DefaultConversationEngine.failedToolSignatures(history)).isEmpty();
    }

    @Test
    void signature_is_independent_of_arg_insertion_order() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("lotteryName", "La Real");
        a.put("amountDOP", 100);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("amountDOP", 100);
        b.put("lotteryName", "La Real");

        assertThat(DefaultConversationEngine.toolSignature("lines_add", a))
                .isEqualTo(DefaultConversationEngine.toolSignature("lines_add", b));
    }

    @Test
    void different_args_are_distinct_signatures() {
        assertThat(DefaultConversationEngine.toolSignature("lines_add", Map.of("numbers", "25-50")))
                .isNotEqualTo(DefaultConversationEngine.toolSignature("lines_add", Map.of("numbers", "12-34")));
    }

    @Test
    void empty_history_has_no_failures() {
        assertThat(DefaultConversationEngine.failedToolSignatures(List.of())).isEmpty();
        assertThat(DefaultConversationEngine.failedToolSignatures(null)).isEmpty();
    }
}
