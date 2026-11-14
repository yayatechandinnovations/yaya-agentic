package com.yayatechandinnovations.yayaagentic.engine;

import com.yayatechandinnovations.yayaagentic.core.IntentFrame;
import com.yayatechandinnovations.yayaagentic.core.Session;
import com.yayatechandinnovations.yayaagentic.core.Turn;
import com.yayatechandinnovations.yayaagentic.knowledge.RetrievedChunk;
import com.yayatechandinnovations.yayaagentic.personality.PersonalityFragment;
import com.yayatechandinnovations.yayaagentic.profile.Profile;

import java.util.List;

/**
 * Assembles the prompt per turn in the fixed order from design §7. The
 * cacheable prefix (items 1–5) and variable suffix (items 6–11) are
 * returned separately so the engine can drive prompt caching.
 */
public interface PromptBuilder {

    PromptPayload build(PersonalityFragment personality,
                        Profile profile,
                        Session session,
                        IntentFrame intent,
                        List<Turn> history,
                        List<RetrievedChunk> retrieved,
                        UserMessage userMessage,
                        String earlierSummary);

    /**
     * As {@link #build}, with the instant the prompt presents as "now". The
     * engine passes the start of the turn for every model round of that turn,
     * so the variable suffix does not change between rounds just because the
     * clock moved.
     */
    default PromptPayload build(PersonalityFragment personality,
                                Profile profile,
                                Session session,
                                IntentFrame intent,
                                List<Turn> history,
                                List<RetrievedChunk> retrieved,
                                UserMessage userMessage,
                                String earlierSummary,
                                java.time.Instant now) {
        return build(personality, profile, session, intent, history, retrieved, userMessage, earlierSummary);
    }

    record PromptPayload(String cacheablePrefix, String variableSuffix) {}
}
