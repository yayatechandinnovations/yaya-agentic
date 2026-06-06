package com.yayatechandinnovations.yayaagentic.engine;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.profile.StartConversationRequest;
import reactor.core.publisher.Flux;

/**
 * The top-level runtime entry point. The API layer calls these three methods
 * and nothing else; everything inside the engine is private behind this SPI.
 */
public interface ConversationEngine {

    StartSessionResult start(StartConversationRequest req, AuthContext auth);

    Flux<TurnEvent> send(Ids.SessionId sessionId, UserMessage message, AuthContext auth);

    void end(Ids.SessionId sessionId, AuthContext auth);
}
