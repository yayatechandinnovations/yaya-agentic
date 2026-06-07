package com.yayatechandinnovations.yayaagentic.api.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.core.IntentFrame;
import com.yayatechandinnovations.yayaagentic.engine.PromptBuilder;
import com.yayatechandinnovations.yayaagentic.persistence.AuditAuthzEntity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Read-only snapshot the playground inspector renders alongside the chat.
 * One round-trip per turn end keeps the UI in sync without subscribing
 * separate streams for each panel.
 */
public final class InspectorDtos {

    private InspectorDtos() {}

    public record InspectorSnapshot(
            IntentDto intent,
            Map<String, Object> workingMemory,
            PromptDto lastPrompt,
            DenialDto lastDenial
    ) {
        public static InspectorSnapshot of(IntentFrame intent,
                                           Map<String, Object> wm,
                                           PromptBuilder.PromptPayload prompt,
                                           AuditAuthzEntity denial,
                                           ObjectMapper json) {
            return new InspectorSnapshot(
                    intent == null ? null : IntentDto.of(intent),
                    wm == null ? Map.of() : wm,
                    prompt == null ? null
                            : new PromptDto(prompt.cacheablePrefix(), prompt.variableSuffix()),
                    denial == null ? null : DenialDto.of(denial, json));
        }
    }

    public record IntentDto(String label,
                            Map<String, Object> slots,
                            List<ParkedIntentDto> parkedStack) {
        public static IntentDto of(IntentFrame frame) {
            return new IntentDto(
                    frame.label(),
                    frame.slots() == null ? Map.of() : frame.slots(),
                    frame.parkedStack() == null ? List.of()
                            : frame.parkedStack().stream()
                                    .map(p -> new ParkedIntentDto(p.label(), p.slots(), p.reason()))
                                    .toList());
        }
    }

    public record ParkedIntentDto(String label, Map<String, Object> slots, String reason) {}

    public record PromptDto(String cacheablePrefix, String variableSuffix) {}

    public record DenialDto(String toolId,
                            String userReason,
                            String auditReason,
                            Map<String, Object> policyTrace,
                            Map<String, Object> args,
                            OffsetDateTime at) {
        public static DenialDto of(AuditAuthzEntity e, ObjectMapper json) {
            return new DenialDto(
                    e.getToolId(),
                    e.getUserReason(),
                    e.getAuditReason(),
                    parseJson(e.getPolicyTraceJson(), json),
                    parseJson(e.getArgsJson(), json),
                    e.getCreatedAt());
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> parseJson(String raw, ObjectMapper json) {
            if (raw == null || raw.isBlank()) return Map.of();
            try {
                return json.readValue(raw, Map.class);
            } catch (Exception ex) {
                return Map.of("_raw", raw);
            }
        }
    }
}
