package com.yayatechandinnovations.yayaagentic.engine.prompt;

import com.yayatechandinnovations.yayaagentic.core.IntentFrame;
import com.yayatechandinnovations.yayaagentic.core.Session;
import com.yayatechandinnovations.yayaagentic.core.Turn;
import com.yayatechandinnovations.yayaagentic.engine.PromptBuilder;
import com.yayatechandinnovations.yayaagentic.engine.UserMessage;
import com.yayatechandinnovations.yayaagentic.knowledge.RetrievedChunk;
import com.yayatechandinnovations.yayaagentic.personality.PersonalityFragment;
import com.yayatechandinnovations.yayaagentic.profile.Profile;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-only decorator. Wraps the real {@link DefaultPromptBuilder} (resolved
 * by exact type), records every payload it produces, and is marked
 * {@code @Primary} so the engine receives it instead of the bare
 * DefaultPromptBuilder.
 */
@Component
@Primary
public class RecordingPromptInspector implements PromptBuilder {

    private final DefaultPromptBuilder delegate;
    private final CopyOnWriteArrayList<PromptPayload> seen = new CopyOnWriteArrayList<>();

    public RecordingPromptInspector(DefaultPromptBuilder delegate) {
        this.delegate = delegate;
    }

    @Override
    public PromptPayload build(PersonalityFragment personality, Profile profile,
                               Session session, IntentFrame intent,
                               List<Turn> history, List<RetrievedChunk> retrieved,
                               UserMessage userMessage) {
        PromptPayload payload = delegate.build(personality, profile, session, intent, history, retrieved, userMessage);
        seen.add(payload);
        return payload;
    }

    public PromptPayload lastPayload() {
        return seen.isEmpty() ? null : seen.get(seen.size() - 1);
    }

    public PromptPayload firstPayload() {
        return seen.isEmpty() ? null : seen.get(0);
    }

    public void clear() {
        seen.clear();
    }
}
