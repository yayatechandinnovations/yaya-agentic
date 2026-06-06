package com.yayatechandinnovations.yayaagentic.recorder.router;

import com.yayatechandinnovations.yayaagentic.core.Session;
import com.yayatechandinnovations.yayaagentic.recorder.ConversationRecorder;
import com.yayatechandinnovations.yayaagentic.recorder.RecorderRouter;
import org.springframework.stereotype.Component;

/**
 * M1 router: always returns the @Primary {@link ConversationRecorder}
 * (i.e. the outbox-wrapped Postgres recorder). M5 replaces this with a
 * {@code recording_strategies}-backed resolver that picks per-session.
 */
@Component
public class DefaultRecorderRouter implements RecorderRouter {

    private final ConversationRecorder primary;

    public DefaultRecorderRouter(ConversationRecorder primary) {
        this.primary = primary;
    }

    @Override
    public ConversationRecorder recorderFor(Session session, RecordingDecisionContext ctx) {
        return primary;
    }
}
