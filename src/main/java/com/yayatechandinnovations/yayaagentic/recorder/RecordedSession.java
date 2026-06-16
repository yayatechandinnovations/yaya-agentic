package com.yayatechandinnovations.yayaagentic.recorder;

import com.yayatechandinnovations.yayaagentic.core.Session;
import com.yayatechandinnovations.yayaagentic.core.Turn;

import java.util.List;

/**
 * What {@link ConversationRecorder#loadSession} returns. The {@code summary}
 * field is populated only when {@link LoadOptions#summarized} is true and the
 * recorder has rotated some turns into a rolling summary — in that case,
 * {@code turns} is the unsummarized tail.
 */
public record RecordedSession(Session session, List<Turn> turns, Summary summary) {

    public RecordedSession(Session session, List<Turn> turns) {
        this(session, turns, null);
    }

    /**
     * @param text           the rolling summary text, never blank when set
     * @param throughTurnIdx the index of the last turn folded into the summary;
     *                       turns with {@code index > throughTurnIdx} are in
     *                       the {@code turns} tail
     */
    public record Summary(String text, int throughTurnIdx) {}
}
