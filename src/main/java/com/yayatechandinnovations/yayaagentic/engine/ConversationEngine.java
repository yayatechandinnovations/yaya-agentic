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
     * Confirmable tools still pause for the confirm UiHint — a tap starts the
     * flow, it does not skip the guardrail. See {@code chat-channel-design.md}.
     */
    Flux<TurnEvent> dispatchAction(Ids.SessionId sessionId, Ids.ToolId tool,
                                   java.util.Map<String, Object> args, AuthContext auth);

    void end(Ids.SessionId sessionId, AuthContext auth);

    /**
     * Open the conversation of a headless run
     * ({@code docs/guides/admin-panel-sessions.md} §17): the profile's agent
     * answering one stored question with nobody present. Unlike
     * {@link #start} it runs no start flow and resolves nothing — the caller
     * has verified {@code principal} and chosen the concrete {@code profile}.
     * The session's channel is {@code TurnChannel.AUTOMATION}; it takes one
     * {@link #sendHeadless} and never a {@link #send}, a
     * {@link #dispatchAction} or a {@link #resume}. Default: unsupported.
     */
    default StartSessionResult startHeadless(Ids.TenantId tenant, Ids.ProfileId profile,
                                             com.yayatechandinnovations.yayaagentic.core.Principal principal,
                                             com.yayatechandinnovations.yayaagentic.headless.HeadlessTurn turn) {
        throw new UnsupportedOperationException("headless runs not supported by this engine");
    }

    /**
     * True when a headless run of {@code profile} would be offered at least
     * one tool — the same selection {@link #sendHeadless} makes for the turn.
     * A profile with none has nothing to answer from, so its runs are refused
     * before they are queued. Default: true (an engine that does not say).
     */
    default boolean offersHeadlessTools(com.yayatechandinnovations.yayaagentic.profile.Profile profile) {
        return true;
    }

    /**
     * The one turn of a headless session. The model is offered only the
     * profile's tools flagged {@code policy.headless} and a call to any other
     * comes back {@code tool_not_available}; nothing pauses for a person — a
     * confirmation, a secret prompt, a step-up, an elicitation or an entity
     * conflict fails the turn with a
     * {@link com.yayatechandinnovations.yayaagentic.headless.HeadlessStop}
     * instead, and no pending state is written. The turn is not admitted
     * against the allowance again: it settles the reservation the
     * {@code HeadlessTurn} carries. Default: unsupported.
     */
    default Flux<TurnEvent> sendHeadless(Ids.SessionId sessionId, UserMessage question, AuthContext auth) {
        return Flux.error(new UnsupportedOperationException("headless runs not supported by this engine"));
    }

    /**
     * True when {@code text}, arriving on {@code channel} (a
     * {@link com.yayatechandinnovations.yayaagentic.core.TurnChannel} value),
     * is or may hold the code of the session's secret prompt: the prompt is
     * pending and the next {@link #send} would take the text as the code or
     * answer it with a fixed sentence, or the prompt lapsed a moment ago and
     * the text looks like its code. Either way {@link #send} keeps it from the
     * model. A text channel asks this as soon as it knows the conversation,
     * so the inbound row never keeps such a text
     * ({@code docs/guides/admin-panel-sessions.md} §15). It changes nothing,
     * and works for a session this instance does not hold in memory.
     */
    default boolean awaitsSecret(Ids.SessionId sessionId, String text, String channel) {
        return false;
    }

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

    /**
     * Record a message an integration delivered to the user on another surface
     * (e.g. a WhatsApp template sent while no conversation was open) as a
     * non-executable SYSTEM turn of this live session, so the user's reply has
     * its context (whatsapp-ops-integration-design.md §6.6). The engine owns turn
     * ordering, so the append goes through it. Returns false when the session is
     * not live here. Default: unsupported (false).
     */
    default boolean appendSystemTurn(Ids.SessionId sessionId, String content,
                                     java.util.Map<String, Object> metadata) {
        return false;
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
