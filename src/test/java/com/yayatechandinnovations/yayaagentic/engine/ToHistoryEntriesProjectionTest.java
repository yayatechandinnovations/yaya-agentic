package com.yayatechandinnovations.yayaagentic.engine;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Turn;
import com.yayatechandinnovations.yayaagentic.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Projection invariant for the recorded transcript → LLM history conversion.
 * <p>
 * The Anthropic Messages API requires every {@code tool_use} block to be
 * immediately followed by a matching {@code tool_result}. The confirmable
 * preview path records an assistant turn with a {@link Turn.ToolCall} but
 * no {@link Turn.ToolResult} (we paused for the user's yes/no), so a naive
 * 1:1 projection would resend that orphan in the next turn and trip:
 * {@code 400 BAD_REQUEST: tool_use ids were found without tool_result
 * blocks immediately after}. These tests pin the filter that drops the
 * orphan from the LLM view while leaving the recorded {@link Turn} intact.
 */
class ToHistoryEntriesProjectionTest {

    private static final Ids.SessionId SID = new Ids.SessionId("s-1");
    private static final Ids.ToolId ECHO = new Ids.ToolId("echo");

    @Test
    void confirmable_preview_then_yes_then_dispatch_produces_no_orphan_tool_use() {
        // Mirrors the actual recorded sequence after a confirm-yes flow:
        //   1. user trigger
        //   2. assistant preview: spoken text + ToolCall(P), NO ToolResult
        //   3. user "yes"
        //   4. assistant dispatch: ToolCall(P) + ToolResult(P)
        // Pre-fix this projected an Assistant(tool_use P) immediately followed
        // by a User("yes") — an orphan tool_use → 400 from Anthropic.
        String previewCallId = "call-preview-1";
        List<Turn> turns = List.of(
                user(0, "echo confirm-me"),
                assistant(1, "Run echo with {text=confirm-me}? (reply yes or no)",
                        List.of(new Turn.ToolCall(previewCallId, ECHO, Map.of("text", "confirm-me"))),
                        List.of()),
                user(2, "yes"),
                assistant(3, "",
                        List.of(new Turn.ToolCall(previewCallId, ECHO, Map.of("text", "confirm-me"))),
                        List.of(new Turn.ToolResult(previewCallId, Turn.ToolResult.Status.OK,
                                Map.of("echo", "confirm-me"), null))));

        List<LlmClient.HistoryEntry> projected = DefaultConversationEngine.toHistoryEntries(turns);

        assertNoOrphanToolUse(projected);

        // Preview turn keeps its spoken text — that's the only signal the
        // LLM needs to know the previous turn paused for confirmation.
        assertThat(projected.get(0)).isInstanceOf(LlmClient.HistoryEntry.User.class);
        LlmClient.HistoryEntry.Assistant preview = (LlmClient.HistoryEntry.Assistant) projected.get(1);
        assertThat(preview.toolCalls()).isEmpty();
        assertThat(preview.content()).contains("reply yes or no");
    }

    @Test
    void confirmable_preview_then_no_drops_the_orphan_call() {
        // After a cancellation:
        //   1. trigger
        //   2. preview (text + orphan ToolCall)
        //   3. "no"
        //   4. "OK — cancelled."
        // The orphan from step 2 would otherwise survive forever in history.
        String previewCallId = "call-preview-2";
        List<Turn> turns = List.of(
                user(0, "echo cancel-me"),
                assistant(1, "Run echo with {text=cancel-me}? (reply yes or no)",
                        List.of(new Turn.ToolCall(previewCallId, ECHO, Map.of("text", "cancel-me"))),
                        List.of()),
                user(2, "no"),
                assistant(3, "OK — cancelled.", List.of(), List.of()));

        List<LlmClient.HistoryEntry> projected = DefaultConversationEngine.toHistoryEntries(turns);

        assertNoOrphanToolUse(projected);
    }

    @Test
    void confirmable_preview_then_unclear_pivot_drops_the_orphan_call() {
        // The unclear-reply pivot path: pending_confirm is dropped and the
        // engine runs the next user message through the normal LLM round.
        // The recorded preview's tool_call is the lingering orphan.
        String previewCallId = "call-preview-3";
        List<Turn> turns = List.of(
                user(0, "echo pivot-me"),
                assistant(1, "Run echo with {text=pivot-me}? (reply yes or no)",
                        List.of(new Turn.ToolCall(previewCallId, ECHO, Map.of("text", "pivot-me"))),
                        List.of()),
                user(2, "hmm, what does this do?"),
                assistant(3, "It echoes whatever you type back to you.",
                        List.of(), List.of()));

        List<LlmClient.HistoryEntry> projected = DefaultConversationEngine.toHistoryEntries(turns);

        assertNoOrphanToolUse(projected);
    }

    @Test
    void normal_dispatch_round_passes_through_unchanged() {
        // The non-confirmable path must still project tool_use + tool_result
        // verbatim so the LLM can paraphrase the result in the continuation.
        String callId = "call-direct";
        List<Turn> turns = List.of(
                user(0, "echo hi"),
                assistant(1, "",
                        List.of(new Turn.ToolCall(callId, ECHO, Map.of("text", "hi"))),
                        List.of(new Turn.ToolResult(callId, Turn.ToolResult.Status.OK,
                                Map.of("echo", "hi"), null))));

        List<LlmClient.HistoryEntry> projected = DefaultConversationEngine.toHistoryEntries(turns);

        assertThat(projected).hasSize(3);
        assertThat(projected.get(0)).isInstanceOf(LlmClient.HistoryEntry.User.class);
        LlmClient.HistoryEntry.Assistant assistant = (LlmClient.HistoryEntry.Assistant) projected.get(1);
        assertThat(assistant.toolCalls()).extracting(LlmClient.ToolCallSpec::callId)
                .containsExactly(callId);
        LlmClient.HistoryEntry.ToolResults results = (LlmClient.HistoryEntry.ToolResults) projected.get(2);
        assertThat(results.results()).extracting(LlmClient.ToolResultSpec::callId)
                .containsExactly(callId);
    }

    @Test
    void empty_text_with_orphan_call_only_collapses_to_nothing() {
        // Edge case: if a future code path ever records an assistant turn
        // with ONLY an orphan tool_call (no spoken text), the projection
        // must skip the whole assistant entry so we don't ship an empty
        // Assistant block — Anthropic would still reject the request.
        List<Turn> turns = List.of(
                user(0, "go"),
                assistant(1, "",
                        List.of(new Turn.ToolCall("orphan", ECHO, Map.of("text", "x"))),
                        List.of()));

        List<LlmClient.HistoryEntry> projected = DefaultConversationEngine.toHistoryEntries(turns);

        assertThat(projected).hasSize(1);
        assertThat(projected.get(0)).isInstanceOf(LlmClient.HistoryEntry.User.class);
    }

    // ---- helpers ----------------------------------------------------

    /**
     * Walks the projected history and fails if an {@code Assistant} entry
     * with a non-empty {@code toolCalls} list is not immediately followed
     * by a {@code ToolResults} entry whose result ids cover every call id.
     * This is exactly what Anthropic enforces server-side.
     */
    private static void assertNoOrphanToolUse(List<LlmClient.HistoryEntry> entries) {
        for (int i = 0; i < entries.size(); i++) {
            if (!(entries.get(i) instanceof LlmClient.HistoryEntry.Assistant a)) continue;
            if (a.toolCalls() == null || a.toolCalls().isEmpty()) continue;

            LlmClient.HistoryEntry next = (i + 1 < entries.size()) ? entries.get(i + 1) : null;
            assertThat(next)
                    .as("assistant tool_use at index %d must be followed by a ToolResults entry", i)
                    .isInstanceOf(LlmClient.HistoryEntry.ToolResults.class);
            java.util.Set<String> resultIds = ((LlmClient.HistoryEntry.ToolResults) next).results().stream()
                    .map(LlmClient.ToolResultSpec::callId)
                    .collect(java.util.stream.Collectors.toSet());
            assertThat(resultIds)
                    .as("assistant tool_use at index %d has call ids without matching tool_results", i)
                    .containsAll(a.toolCalls().stream().map(LlmClient.ToolCallSpec::callId).toList());
        }
    }

    private static Turn user(int index, String content) {
        return new Turn(new Ids.TurnId("t-u-" + index), SID, index,
                Turn.Role.USER, content, List.of(), List.of(), List.of(),
                null, Map.of(), Instant.EPOCH);
    }

    private static Turn assistant(int index, String content,
                                  List<Turn.ToolCall> calls,
                                  List<Turn.ToolResult> results) {
        return new Turn(new Ids.TurnId("t-a-" + index), SID, index,
                Turn.Role.ASSISTANT, content, calls, results, List.of(),
                null, Map.of(), Instant.EPOCH);
    }
}
