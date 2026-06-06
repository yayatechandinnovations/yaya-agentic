package com.yayatechandinnovations.yayaagentic.core;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One unit of conversation: a single role's contribution plus any tool
 * activity that happened to produce it. Recorded by the
 * {@code ConversationRecorder} (see design §5.9).
 */
public record Turn(
        Ids.TurnId id,
        Ids.SessionId sessionId,
        int index,
        Role role,
        String content,
        List<ToolCall> toolCalls,
        List<ToolResult> toolResults,
        List<String> retrievedChunkIds,
        ModelInfo modelInfo,
        Map<String, Object> metadata,
        Instant createdAt
) {
    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }

    public record ToolCall(String callId, Ids.ToolId toolId, Map<String, Object> args) {}
    public record ToolResult(String callId, Status status, Object value, String error) {
        public enum Status { OK, DENIED, FAILED }
    }
    public record ModelInfo(String provider, String model, Integer tokensIn, Integer tokensOut) {}
}
