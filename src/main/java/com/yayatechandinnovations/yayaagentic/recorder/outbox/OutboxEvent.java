package com.yayatechandinnovations.yayaagentic.recorder.outbox;

import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.util.UUID;

public record OutboxEvent(
        Kind kind,
        Ids.TenantId tenant,
        UUID sessionId,
        UUID turnId,
        String payloadJson
) {
    public enum Kind { SESSION_STARTED, TURN_RECORDED, SESSION_ENDED }
}
