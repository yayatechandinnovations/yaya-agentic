package com.yayatechandinnovations.yayaagentic.llm;

import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * Single LLM façade the engine talks to. Each {@link #stream} call is ONE
 * model round. Multi-turn agentic reasoning (LLM → tool → LLM → …) is
 * driven by the engine, which calls {@code stream} multiple times with a
 * growing {@link LlmRequest#history}.
 *
 * <p>The prompt is split into a <em>cacheable prefix</em> (stable per
 * tenant + profile + version — personality + profile fragment + tool
 * schemas + safety rules) and a <em>variable suffix</em> (per-turn
 * context — intent frame, working memory). History entries carry both
 * plain text and structured tool calls / results so a continuation can
 * faithfully reconstruct what happened earlier in the same user turn.</p>
 */
public interface LlmClient {

    Flux<LlmEvent> stream(LlmRequest request);

    // ---- Request --------------------------------------------------------

    record LlmRequest(
            String cacheablePrefix,
            String variableSuffix,
            List<HistoryEntry> history,
            List<ToolDefinition> availableTools
    ) {}

    /** What the LLM is told it can call. */
    record ToolDefinition(String name, String description, String inputSchemaJson) {}

    // ---- History --------------------------------------------------------

    sealed interface HistoryEntry permits HistoryEntry.User,
                                          HistoryEntry.Assistant,
                                          HistoryEntry.ToolResults {

        /** Plain user message. */
        record User(String content) implements HistoryEntry {}

        /** Assistant turn: optional reasoning text + zero-or-more proposed
         *  tool calls. Empty {@code toolCalls} = the assistant finished
         *  with text only; non-empty = the assistant proposed tools. */
        record Assistant(String content, List<ToolCallSpec> toolCalls) implements HistoryEntry {}

        /** Engine-side response: one result per matching {@link ToolCallSpec}. */
        record ToolResults(List<ToolResultSpec> results) implements HistoryEntry {}
    }

    /** A proposed call. {@code callId} matches the corresponding result. */
    record ToolCallSpec(String callId, String toolName, Map<String, Object> args) {}

    /** A result of an engine-side dispatch. */
    record ToolResultSpec(String callId, Object value, boolean isError) {}

    // ---- Streamed events ------------------------------------------------

    sealed interface LlmEvent permits LlmEvent.TokenChunk,
                                       LlmEvent.ToolUseProposal,
                                       LlmEvent.Done {

        record TokenChunk(String text) implements LlmEvent {}

        record ToolUseProposal(String callId,
                               String toolName,
                               Map<String, Object> args) implements LlmEvent {}

        /** Terminal signal. {@link StopReason} is provider-agnostic — each
         *  {@link LlmClient} implementation translates its wire-level
         *  reason (Anthropic's {@code end_turn}/{@code tool_use}, OpenAI's
         *  {@code stop}/{@code tool_calls}, …) into this enum so the
         *  engine doesn't need to know which provider it's talking to. */
        record Done(StopReason stopReason) implements LlmEvent {}
    }

    /** Why the model stopped producing tokens. The engine loops on
     *  {@link #TOOL_USE}; everything else terminates the turn. */
    enum StopReason {
        /** The model proposed at least one tool call and is waiting for results. */
        TOOL_USE,
        /** The model finished its reply cleanly. */
        END_TURN,
        /** The model was cut off by the response token limit. */
        MAX_TOKENS,
        /** Provider-specific reason we don't have a first-class mapping for
         *  yet. The engine treats this the same as END_TURN. */
        OTHER
    }
}
