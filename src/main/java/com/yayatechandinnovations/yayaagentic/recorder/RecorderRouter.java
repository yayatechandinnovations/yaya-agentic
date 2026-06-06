package com.yayatechandinnovations.yayaagentic.recorder;

import com.yayatechandinnovations.yayaagentic.core.Session;

import java.util.Map;

/**
 * Resolves a {@link RecordingStrategy} into the concrete recorder used for a
 * given session. From the engine's perspective there is always exactly one
 * recorder behind the SPI.
 */
public interface RecorderRouter {

    ConversationRecorder recorderFor(Session session, RecordingDecisionContext ctx);

    record RecordingDecisionContext(Map<String, Object> attributes) {}
}
