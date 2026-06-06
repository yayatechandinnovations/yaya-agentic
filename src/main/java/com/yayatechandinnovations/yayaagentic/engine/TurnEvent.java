package com.yayatechandinnovations.yayaagentic.engine;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Turn;

import java.util.List;
import java.util.Map;

/**
 * Streaming events emitted during a single turn. Mirrors the SSE event
 * names in design §9.
 */
public sealed interface TurnEvent
        permits TurnEvent.Token,
                TurnEvent.ToolCall,
                TurnEvent.ToolResult,
                TurnEvent.Citation,
                TurnEvent.UiHint,
                TurnEvent.End {

    record Token(String text) implements TurnEvent {}

    record ToolCall(String callId, Ids.ToolId tool, Map<String, Object> args) implements TurnEvent {}

    record ToolResult(String callId, Turn.ToolResult.Status status, Object value, String error)
            implements TurnEvent {}

    record Citation(String chunkId, Ids.KnowledgeSourceId source, String title, String url)
            implements TurnEvent {}

    record UiHint(String kind, Map<String, Object> payload) implements TurnEvent {}

    record End(Ids.TurnId turnId, Integer tokensIn, Integer tokensOut) implements TurnEvent {

        public static End of(Ids.TurnId id, int in, int out) { return new End(id, in, out); }

        public static UiHint quickReplies(List<String> replies) {
            return new UiHint("quick_replies", Map.of("items", replies));
        }
    }
}
