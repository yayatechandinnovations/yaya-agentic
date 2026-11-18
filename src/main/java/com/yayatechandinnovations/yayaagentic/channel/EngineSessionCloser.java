package com.yayatechandinnovations.yayaagentic.channel;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.engine.ConversationEngine;
import com.yayatechandinnovations.yayaagentic.persistence.SessionRepository;
import com.yayatechandinnovations.yayaagentic.recorder.ConversationRecorder;
import com.yayatechandinnovations.yayaagentic.recorder.Operations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Ends the engine session behind a channel conversation when the channel
 * closes it (idle timeout, a new-session keyword, a lost mapping), so
 * {@code sessions.ended_at} is set and the thread stops counting as open —
 * on {@code latest}, as {@code elsewhere}, anywhere. Idempotent: a session
 * the engine no longer holds, or one already ended, is left as it is.
 */
@Component
public class EngineSessionCloser {

    private static final Logger LOG = LoggerFactory.getLogger(EngineSessionCloser.class);

    private final ObjectProvider<ConversationEngine> engine;
    private final SessionRepository sessions;
    private final ConversationRecorder recorder;

    public EngineSessionCloser(ObjectProvider<ConversationEngine> engine,
                               SessionRepository sessions,
                               @Qualifier("postgresConversationRecorder") ConversationRecorder recorder) {
        this.engine = engine;
        this.sessions = sessions;
        this.recorder = recorder;
    }

    public void endIfOpen(Ids.SessionId sessionId, String reason) {
        if (sessionId == null || sessionId.value() == null) return;
        try {
            ConversationEngine live = engine.getIfAvailable();
            if (live != null) live.end(sessionId, null);
            // A session that fell out of the engine (restart) has no live copy
            // for it to close; the stored one is closed here.
            UUID id = UUID.fromString(sessionId.value());
            sessions.findById(id).filter(row -> row.getEndedAt() == null).ifPresent(row ->
                    recorder.onSessionEnded(sessionId, new Operations.SessionEndContext(Instant.now(),
                            reason == null ? "channel-closed" : "channel-closed:" + reason)));
        } catch (RuntimeException e) {
            LOG.warn("engine session {} not ended after channel close ({}): {}", sessionId.value(), reason,
                    e.toString());
        }
    }
}
