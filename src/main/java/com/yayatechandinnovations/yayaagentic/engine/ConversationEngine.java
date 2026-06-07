package com.yayatechandinnovations.yayaagentic.engine;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.IntentFrame;
import com.yayatechandinnovations.yayaagentic.profile.StartConversationRequest;
import reactor.core.publisher.Flux;

import java.util.Optional;

/**
 * The top-level runtime entry point. The API layer calls these three methods
 * and nothing else; everything inside the engine is private behind this SPI.
 */
public interface ConversationEngine {

    StartSessionResult start(StartConversationRequest req, AuthContext auth);

    Flux<TurnEvent> send(Ids.SessionId sessionId, UserMessage message, AuthContext auth);

    void end(Ids.SessionId sessionId, AuthContext auth);

    /** Most recent {@link IntentFrame} for the session, or empty if unknown. */
    Optional<IntentFrame> currentIntent(Ids.SessionId sessionId);

    /** Cacheable + variable prompt halves from the most recent LLM round in
     *  this session, for the playground inspector. */
    Optional<PromptBuilder.PromptPayload> lastPrompt(Ids.SessionId sessionId);
}
