package com.yayatechandinnovations.yayaagentic.engine;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.IntentFrame;
import com.yayatechandinnovations.yayaagentic.core.Session;
import com.yayatechandinnovations.yayaagentic.knowledge.RetrievalResult;
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

    /**
     * Client-initiated action: run one whitelisted tool through the SAME
     * executor + Authorizer path as a chat message (two doors, one lock). The
     * tool must be in the session profile's capabilities AND
     * {@code clientInvocable}; otherwise {@link ActionNotPermittedException}.
     * Confirmable tools (e.g. checkout) still pause for the confirm UiHint — a
     * tap starts the flow, it does not skip the guardrail. See
     * {@code ecommerce-chat-design.md} §9.
     */
    Flux<TurnEvent> dispatchAction(Ids.SessionId sessionId, Ids.ToolId tool,
                                   java.util.Map<String, Object> args, AuthContext auth);

    void end(Ids.SessionId sessionId, AuthContext auth);

    /**
     * Rehydrate a persisted session into the active set so its rightful owner
     * can continue it after it fell out of memory — e.g. an operator replying to
     * a system-participant message that was delivered while they were offline
     * (system-messaging-design.md §12). The caller is re-authenticated and must
     * own the session; the session's principal is refreshed to the current
     * verified identity so the reply turn authorizes against the operator's real
     * scopes. Default: unsupported.
     */
    default Session resume(Ids.SessionId sessionId, AuthContext auth) {
        throw new UnsupportedOperationException("resume not supported by this engine");
    }

    /** Most recent {@link IntentFrame} for the session, or empty if unknown. */
    Optional<IntentFrame> currentIntent(Ids.SessionId sessionId);

    /** Cacheable + variable prompt halves from the most recent LLM round in
     *  this session, for the playground inspector. */
    Optional<PromptBuilder.PromptPayload> lastPrompt(Ids.SessionId sessionId);

    /** Most recent retrieval (sources considered/denied, chunks + scores)
     *  for this session, for the playground retrieval inspector. */
    Optional<RetrievalResult> lastRetrieval(Ids.SessionId sessionId);

    /** Tool calls + their results from the most recent user turn, for the
     *  playground inspector's tool-activity panel. Empty when the latest turn
     *  called no tools. */
    Optional<java.util.List<ToolActivity>> lastToolActivity(Ids.SessionId sessionId);

    /**
     * One tool invocation from a turn. {@code status} is {@code "PENDING"}
     * until the dispatch resolves, then the {@code Turn.ToolResult.Status}
     * name ({@code OK} / {@code FAILED} / {@code DENIED} / …). {@code result}
     * is the returned value on success or the structured error payload on
     * failure; {@code error} carries the raw failure text when present.
     *
     * <p>The auth fields are HTTP-tool dispatch diagnostics for the playground
     * auth-flow inspector: {@code authForwarding} is the forwarding mode
     * (NONE/PRINCIPAL_TOKEN/SERVICE_TOKEN), {@code authHeaderPresent} is whether
     * an auth header was actually attached, {@code httpStatus} is the response
     * status (e.g. 401). All null for non-HTTP tools.
     */
    record ToolActivity(String callId,
                        String toolId,
                        java.util.Map<String, Object> args,
                        String status,
                        Object result,
                        String error,
                        String authForwarding,
                        Boolean authHeaderPresent,
                        Integer httpStatus) {}
}
