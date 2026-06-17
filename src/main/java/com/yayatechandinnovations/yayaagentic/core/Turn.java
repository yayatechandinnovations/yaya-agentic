package com.yayatechandinnovations.yayaagentic.core;

import com.yayatechandinnovations.yayaagentic.core.entity.EntityRef;

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

    /**
     * {@code capturedEntities} (M2.10) holds the {@link EntityRef}s produced
     * by this tool call's declared captures. Recorded per turn so a session
     * resume can rehydrate
     * {@link com.yayatechandinnovations.yayaagentic.core.entity.EntityMemory}.
     * Empty (and harmless) for tools that don't declare captures.
     */
    public record ToolResult(
            String callId,
            Status status,
            Object value,
            String error,
            List<EntityRef> capturedEntities
    ) {
        public enum Status { OK, DENIED, FAILED }

        public ToolResult {
            capturedEntities = capturedEntities == null ? List.of() : List.copyOf(capturedEntities);
        }

        /**
         * Compat constructor for the many dispatchers that produce a result
         * before captures are extracted. The engine attaches captures by
         * rebuilding the record via {@link #withCapturedEntities(List)}.
         */
        public ToolResult(String callId, Status status, Object value, String error) {
            this(callId, status, value, error, List.of());
        }

        public ToolResult withCapturedEntities(List<EntityRef> captured) {
            return new ToolResult(callId, status, value, error,
                    captured == null ? List.of() : captured);
        }
    }

    public record ModelInfo(String provider, String model, Integer tokensIn, Integer tokensOut) {}
}
