package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.core.TurnChannel;
import com.yayatechandinnovations.yayaagentic.api.dto.InspectorDtos;
import com.yayatechandinnovations.yayaagentic.api.dto.SessionDtos;
import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.auth.AuthenticationException;
import com.yayatechandinnovations.yayaagentic.auth.Authenticator;
import com.yayatechandinnovations.yayaagentic.auth.PrincipalKinds;
import com.yayatechandinnovations.yayaagentic.auth.playground.ActAs;
import com.yayatechandinnovations.yayaagentic.auth.playground.ActAsMaterializer;
import com.yayatechandinnovations.yayaagentic.auth.playground.PlaygroundActAsRegistry;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatRateLimiter;
import com.yayatechandinnovations.yayaagentic.channel.websse.WebSseChannelAdapter;
import com.yayatechandinnovations.yayaagentic.config.SessionsApiProperties;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.core.Turn;
import com.yayatechandinnovations.yayaagentic.engine.ActionNotPermittedException;
import com.yayatechandinnovations.yayaagentic.channel.MediaTranscriber;
import com.yayatechandinnovations.yayaagentic.engine.ConversationEngine;
import com.yayatechandinnovations.yayaagentic.engine.DefaultConversationEngine;
import com.yayatechandinnovations.yayaagentic.engine.handoff.ChannelHandoffService;
import com.yayatechandinnovations.yayaagentic.engine.handoff.HandoffException;
import com.yayatechandinnovations.yayaagentic.engine.secret.SecretPrompts;
import com.yayatechandinnovations.yayaagentic.engine.StartSessionResult;
import com.yayatechandinnovations.yayaagentic.engine.TurnEvent;
import com.yayatechandinnovations.yayaagentic.engine.UserMessage;
import com.yayatechandinnovations.yayaagentic.memory.WorkingMemory;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.web.OperatorContext;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceAccessHelper;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceNotFoundException;
import com.yayatechandinnovations.yayaagentic.outreach.media.MediaTokenSigner;
import com.yayatechandinnovations.yayaagentic.persistence.SessionEntity;
import com.yayatechandinnovations.yayaagentic.persistence.SessionRepository;
import com.yayatechandinnovations.yayaagentic.profile.Profile;
import com.yayatechandinnovations.yayaagentic.profile.ProfileRegistry;
import com.yayatechandinnovations.yayaagentic.profile.ProfileResolverChain;
import com.yayatechandinnovations.yayaagentic.profile.StartConversationRequest;
import com.yayatechandinnovations.yayaagentic.recorder.ConversationRecorder;
import com.yayatechandinnovations.yayaagentic.recorder.TurnRange;
import com.yayatechandinnovations.yayaagentic.tenant.OriginEnforcer;
import com.yayatechandinnovations.yayaagentic.usage.QuotaExceededException;
import com.yayatechandinnovations.yayaagentic.usage.QuotaRefusals;
import com.yayatechandinnovations.yayaagentic.usage.UsageMeter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The token-authenticated session API. Two kinds of caller share it:
 *
 * <ul>
 *   <li><b>A tenant's own app</b>, acting as its signed-in user: every request
 *       carries that user's bearer token. The session belongs to the verified
 *       subject — every later call must authenticate as the same subject in the
 *       same tenant, and the token on <i>that</i> call is the one forwarded to
 *       {@code PRINCIPAL_TOKEN} tools. Contract:
 *       {@code docs/guides/admin-panel-sessions.md}.</li>
 *   <li><b>The operator playground</b>, which may start a session with no
 *       end-user identity (or an {@code actAs} credential). A session started
 *       anonymously stays capability-scoped by its unguessable id, as before.</li>
 * </ul>
 *
 * <p>The profile is never just the client's choice: the resolver chain picks
 * it, and a profile that declares {@code metadata.principalKinds} only opens
 * for a principal of one of those kinds ({@link PrincipalKinds}).
 */
@RestController
@RequestMapping("/v1/sessions")
public class SessionController {

    private static final Logger LOG = LoggerFactory.getLogger(SessionController.class);
    private static final Duration MINUTE = Duration.ofMinutes(1);

    private final ConversationEngine engine;
    private final OriginEnforcer originEnforcer;
    private final SessionRepository sessions;
    private final ActAsMaterializer actAsMaterializer;
    private final PlaygroundActAsRegistry actAsRegistry;
    private final WebSseChannelAdapter webSse;
    private final Authenticator authenticator;
    private final ProfileResolverChain resolverChain;
    private final ProfileRegistry profiles;
    private final PrincipalKinds principalKinds;
    private final ChatRateLimiter rateLimiter;
    private final SessionsApiProperties props;
    private final ConversationRecorder recorder;
    private final WorkingMemory workingMemory;
    private final UsageMeter usage;
    private final ObjectProvider<MediaTokenSigner> mediaTokens;
    private final InspectorService inspector;
    private final WorkspaceAccessHelper workspaceAccess;
    private final ChannelHandoffService handoffs;
    private final ObjectProvider<MediaTranscriber> transcriber;

    public SessionController(ConversationEngine engine,
                             OriginEnforcer originEnforcer,
                             SessionRepository sessions,
                             ActAsMaterializer actAsMaterializer,
                             PlaygroundActAsRegistry actAsRegistry,
                             WebSseChannelAdapter webSse,
                             Authenticator authenticator,
                             ProfileResolverChain resolverChain,
                             ProfileRegistry profiles,
                             PrincipalKinds principalKinds,
                             ChatRateLimiter rateLimiter,
                             SessionsApiProperties props,
                             @Qualifier("postgresConversationRecorder") ConversationRecorder recorder,
                             WorkingMemory workingMemory,
                             UsageMeter usage,
                             ObjectProvider<MediaTokenSigner> mediaTokens,
                             InspectorService inspector,
                             WorkspaceAccessHelper workspaceAccess,
                             ChannelHandoffService handoffs,
                             ObjectProvider<MediaTranscriber> transcriber) {
        this.engine = engine;
        this.originEnforcer = originEnforcer;
        this.sessions = sessions;
        this.actAsMaterializer = actAsMaterializer;
        this.actAsRegistry = actAsRegistry;
        this.webSse = webSse;
        this.authenticator = authenticator;
        this.resolverChain = resolverChain;
        this.profiles = profiles;
        this.principalKinds = principalKinds;
        this.rateLimiter = rateLimiter;
        this.props = props;
        this.recorder = recorder;
        this.workingMemory = workingMemory;
        this.usage = usage;
        this.mediaTokens = mediaTokens;
        this.inspector = inspector;
        this.workspaceAccess = workspaceAccess;
        this.handoffs = handoffs;
        this.transcriber = transcriber;
    }

    /* ------------------------------- start -------------------------------- */

    @PostMapping
    public Mono<SessionDtos.StartSessionResponse> start(@RequestBody SessionDtos.StartSessionRequest body,
                                                        ServerWebExchange exchange) {
        Ids.TenantId tenant = new Ids.TenantId(body.tenant() == null ? "default" : body.tenant());
        return Mono.fromSupplier(() -> {
            enforceOrigin(tenant.value(), exchange);

            // Explicit id + version = pinned to that exact version; explicit id
            // alone = unpinned (latest resolved at session start). See §5.2.
            Optional<Ids.ProfileId> explicit = body.profileId() == null
                    ? Optional.empty()
                    : Optional.of(body.profileVersion() == null
                            ? Ids.ProfileId.latest(body.profileId())
                            : new Ids.ProfileId(body.profileId(), body.profileVersion()));

            // The label is the client's own, except the one that marks the
            // conversation of a headless run: no client can open one of those.
            StartConversationRequest req = new StartConversationRequest(tenant, explicit,
                    body.channel() == null || TurnChannel.isReserved(body.channel()) ? "web" : body.channel(),
                    body.hints() == null ? Map.of() : body.hints());

            Optional<ActAs> actAs = Optional.ofNullable(body.actAs());
            AuthContext auth = actAsMaterializer.applyIfPresent(authContext(tenant, exchange), actAs);
            // Verified once, here, against the tenant the request names; the
            // engine receives the result instead of re-reading the headers.
            Principal principal = authenticate(auth);
            AuthContext verified = new AuthContext(tenant, auth.headers(), Optional.of(principal));
            // A start may run a start flow and have the model write the opening.
            // It is not a message, so it has its own ceiling per signed-in user.
            if (isIdentified(principal.subject())) {
                requireWithinLimit("sess-start", tenant.value() + ":" + principal.subject(),
                        props.sessionStartsPerMinuteOrDefault());
            }

            // The profile comes from the server's rules. A requested id is only
            // a request: it must be installed for this tenant and admit this
            // kind of principal.
            Optional<Ids.ProfileId> resolved = resolverChain.resolve(req, verified, principal);
            if (resolved.isPresent()) {
                Optional<Profile> profile = resolved.get().isLatest()
                        ? profiles.findLatest(tenant, resolved.get())
                        : profiles.find(tenant, resolved.get());
                if (profile.isEmpty()) {
                    throw new ChatApiException(HttpStatus.NOT_FOUND, "profile_not_available",
                            "that assistant is not available for this tenant", 0);
                }
                if (props.enforcePrincipalKindsOrDefault() && !principalKinds.admits(profile.get(), principal)) {
                    LOG.warn("session start refused: profile {} admits kinds {} but principal '{}' of tenant '{}' has {}",
                            profile.get().id().value(), PrincipalKinds.declaredBy(profile.get()),
                            principal.subject(), tenant.value(), principalKinds.kindsOf(tenant, principal));
                    throw new ChatApiException(HttpStatus.FORBIDDEN, "profile_not_permitted",
                            "this account cannot open that assistant", 0);
                }
                // Pin what was checked, so the engine starts exactly that version.
                req = new StartConversationRequest(tenant, Optional.of(profile.get().id()),
                        req.channel(), req.hints());
            }

            StartSessionResult result = engine.start(req, verified.onChannel(TurnChannel.APP));
            actAs.ifPresent(spec -> actAsRegistry.put(result.session().id(), spec));
            return SessionDtos.StartSessionResponse.of(
                    result.session().id(), result.profile(), result.greeting(), result.quickReplies(),
                    result.clientTraits());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /* ------------------------------ messages ------------------------------ */

    @PostMapping(value = "/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> sendMessage(@PathVariable("id") String id,
                                                     @RequestBody SessionDtos.MessageRequest body,
                                                     ServerWebExchange exchange) {
        Ids.SessionId sessionId = new Ids.SessionId(id);
        return Flux.defer(() -> {
            Caller caller = admit(id, exchange, true);
            if (caller.owned()) requireWithinLimit("sess-msg", caller);

            Map<String, Object> attachments = new LinkedHashMap<>();
            // Structured selection (confirm / cancel by callId, a banca pick): the
            // engine reads it before the text and validates it against the frame
            // it actually showed.
            if (body.selection() != null && body.selection().kind() != null) {
                var sel = body.selection();
                Map<String, Object> selMap = new LinkedHashMap<>();
                selMap.put("kind", sel.kind());
                if (sel.code() != null) selMap.put("code", sel.code());
                if (sel.callId() != null) selMap.put("callId", sel.callId());
                if (sel.itemIds() != null) selMap.put("itemIds", sel.itemIds());
                // The answer to a secret prompt. The engine takes it out of the
                // message before anything is stored or shown to the model.
                if (sel.value() != null) selMap.put("value", sel.value());
                // A bound action's tap (kind "action") and a tapped quick reply
                // (kind "option": id + what it sends) — assistant-channels §2.2.
                if (sel.actionId() != null) selMap.put("actionId", sel.actionId());
                if (sel.id() != null) selMap.put("id", sel.id());
                if (sel.send() != null) selMap.put("send", sel.send());
                attachments.put("_selection", selMap);
            }
            Map<String, Object> clientContext = ClientContexts.validate(body.clientContext());
            if (clientContext != null) {
                attachments.put(DefaultConversationEngine.CLIENT_CONTEXT_KEY, clientContext);
            }
            UserMessage message = new UserMessage(body.text() == null ? "" : body.text(), attachments);

            rehydrate(sessionId, caller);
            return toSse(engine.send(sessionId, message, caller.auth().onChannel(TurnChannel.APP))
                    .onErrorMap(SessionController::isUnknownSession, e -> ChatApiException.sessionNotFound()),
                    caller);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Client-initiated action (a tap on a rendered affordance, e.g. "Load more"):
     * runs one whitelisted tool through the same executor + Authorizer as a chat
     * message, streaming back the same SSE shape (ToolResult + any UiHint). A
     * refused action (tool not in profile / not clientInvocable) maps to 403 with
     * no body — the tool's existence is never leaked. See chat-channel-design.md.
     */
    @PostMapping(value = "/{id}/actions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> action(@PathVariable("id") String id,
                                                @RequestBody SessionDtos.ActionRequest body,
                                                ServerWebExchange exchange) {
        Ids.SessionId sessionId = new Ids.SessionId(id);
        return Flux.defer(() -> {
            Caller caller = admit(id, exchange, true);
            if (caller.owned()) requireWithinLimit("sess-act", caller);
            rehydrate(sessionId, caller);
            return toSse(engine.dispatchAction(sessionId, new Ids.ToolId(body.tool()),
                    body.args() == null ? Map.of() : body.args(), caller.auth().onChannel(TurnChannel.APP))
                    .onErrorMap(SessionController::isUnknownSession, e -> ChatApiException.sessionNotFound()),
                    caller);
        }).subscribeOn(Schedulers.boundedElastic())
          .onErrorMap(ActionNotPermittedException.class,
                e -> new ResponseStatusException(HttpStatus.FORBIDDEN));
    }

    @PostMapping("/{id}/end")
    public Mono<Void> end(@PathVariable("id") String id, ServerWebExchange exchange) {
        Ids.SessionId sessionId = new Ids.SessionId(id);
        return Mono.<Void>fromRunnable(() -> {
            Caller caller = admit(id, exchange, false);
            engine.end(sessionId, caller.auth());
            actAsRegistry.remove(sessionId);
            // A session that fell out of memory (restart) has no live copy for
            // the engine to close; close the stored one so it stops being offered.
            if (caller.owned() && caller.row().getEndedAt() == null) {
                recorder.onSessionEnded(sessionId,
                        new com.yayatechandinnovations.yayaagentic.recorder.Operations.SessionEndContext(
                                Instant.now(), "client-ended"));
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /* --------------------------- history / resume -------------------------- */

    /**
     * The caller's most recent open session in {@code tenant} (on
     * {@code profileId} when given, born on {@code channel} when given), so a
     * client can reopen where it was. With a {@code channel}, the response
     * also names the user's open conversation on another channel, when it is
     * recent, so the client can offer to continue it here (§6, §18).
     */
    @GetMapping("/latest")
    public Mono<SessionDtos.LatestSessionResponse> latest(
            @RequestParam("tenant") String tenantId,
            @RequestParam(value = "profileId", required = false) String profileId,
            @RequestParam(value = "history", defaultValue = "true") boolean history,
            @RequestParam(value = "channel", required = false) String channel,
            ServerWebExchange exchange) {
        return Mono.fromSupplier(() -> {
            Ids.TenantId tenant = new Ids.TenantId(tenantId);
            enforceOrigin(tenantId, exchange);
            Principal principal = requireIdentified(authenticate(authContext(tenant, exchange)));
            String profile = profileId == null || profileId.isBlank() ? null : profileId;
            String born = channel == null || channel.isBlank() || TurnChannel.isReserved(channel) ? null : channel;
            SessionDtos.ElsewhereSession elsewhere = born == null ? null
                    : handoffs.elsewhere(tenantId, principal.subject(), profile, born).map(e ->
                            new SessionDtos.ElsewhereSession(e.session().getId().toString(), e.channel(),
                                    e.lastActivityAt().toString(), e.turns(),
                                    new SessionDtos.ElsewhereSession.Preview(e.userPreview(), e.assistantPreview()),
                                    e.pending()))
                    .orElse(null);

            // Never the conversation of a headless run: a panel must not reopen it.
            Optional<SessionEntity> found = sessions.findReopenable(tenantId, principal.subject(), profile, born,
                    org.springframework.data.domain.PageRequest.of(0, 1)).stream().findFirst();
            if (found.isEmpty()) return SessionDtos.LatestSessionResponse.none(elsewhere);

            SessionEntity row = found.get();
            Ids.SessionId sid = new Ids.SessionId(row.getId().toString());
            List<Turn> turns = recorder.loadTurns(sid, TurnRange.all());
            Instant lastActivity = turns.isEmpty()
                    ? row.getCreatedAt().toInstant() : turns.get(turns.size() - 1).createdAt();
            boolean expired = isExpired(row, profileOf(row).orElse(null));
            return new SessionDtos.LatestSessionResponse(
                    expired ? "EXPIRED" : "RESUMABLE",
                    row.getId().toString(), row.getProfileId(), row.getProfileVersion(),
                    row.getCreatedAt().toInstant().toString(), lastActivity.toString(),
                    history ? display(turns, tenant) : List.of(),
                    expired ? null : pendingConfirm(sid),
                    expired ? null : pendingSecret(sid),
                    expired ? null : pendingPlan(sid),
                    lastToolResults(turns),
                    elsewhere);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Continue here what the user was doing on another channel: the context of
     * {@code fromSessionId} is copied into this session as one framed SYSTEM
     * turn (§18). The source is not touched, and a session takes one hand-off.
     */
    @PostMapping("/{id}/handoff")
    public Mono<SessionDtos.HandoffResponse> handoff(@PathVariable("id") String id,
                                                     @RequestBody SessionDtos.HandoffRequest body,
                                                     ServerWebExchange exchange) {
        return Mono.fromSupplier(() -> {
            Caller caller = admit(id, exchange, true);
            if (!caller.owned()) throw ChatApiException.sessionNotFound();
            if (body == null || body.fromSessionId() == null || body.fromSessionId().isBlank()) {
                throw new ChatApiException(HttpStatus.BAD_REQUEST, "from_session_required",
                        "fromSessionId is required", 0);
            }
            try {
                ChannelHandoffService.Result result = handoffs.perform(caller.row(),
                        new Ids.SessionId(body.fromSessionId().trim()), caller.auth());
                return new SessionDtos.HandoffResponse(result.fromSessionId().value(), result.fromChannel(),
                        display(result.carried(), caller.auth().tenant(), result.fromChannel()));
            } catch (HandoffException refused) {
                throw new ChatApiException(switch (refused.code()) {
                    case HandoffException.SESSION_NOT_FOUND -> HttpStatus.NOT_FOUND;
                    case HandoffException.EXISTS -> HttpStatus.CONFLICT;
                    case HandoffException.SOURCE_TOO_OLD -> HttpStatus.GONE;
                    default -> HttpStatus.FORBIDDEN;
                }, refused.code(), refused.getMessage(), 0);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** What this platform offers a panel beyond text, so it can hide what is not there (§18). */
    @GetMapping("/features")
    public Mono<SessionDtos.FeaturesResponse> features(@RequestParam("tenant") String tenantId,
                                                       ServerWebExchange exchange) {
        return Mono.fromSupplier(() -> {
            enforceOrigin(tenantId, exchange);
            requireIdentified(authenticate(authContext(new Ids.TenantId(tenantId), exchange)));
            return new SessionDtos.FeaturesResponse(true, true, transcriber.getIfAvailable() != null, true, true);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/history")
    public Mono<SessionDtos.HistoryResponse> history(@PathVariable("id") String id,
                                                     ServerWebExchange exchange) {
        return Mono.fromSupplier(() -> {
            Caller caller = admit(id, exchange, false);
            if (!caller.owned()) throw ChatApiException.sessionNotFound();
            Ids.SessionId sid = new Ids.SessionId(id);
            List<Turn> turns = recorder.loadTurns(sid, TurnRange.all());
            return new SessionDtos.HistoryResponse(id,
                    display(turns, caller.auth().tenant()),
                    caller.row().getEndedAt() == null ? pendingConfirm(sid) : null,
                    caller.row().getEndedAt() == null ? pendingSecret(sid) : null,
                    caller.row().getEndedAt() == null ? pendingPlan(sid) : null,
                    lastToolResults(turns));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /* ------------------------------- inspector ----------------------------- */

    /**
     * The engine's view of a session (prompt halves, working memory, tool
     * results) for the operator console. It is never public: a session id
     * alone opens nothing.
     *
     * <ul>
     *   <li>A playground session (started with no end-user identity, or with
     *       an operator's {@code actAs} credential) is read by a signed-in
     *       operator who is at least MEMBER of the session's workspace.</li>
     *   <li>A session a user's app started is read by that same user (a valid
     *       token with the owning subject), or by a workspace ADMIN / an API
     *       key that reaches the workspace at ADMIN.</li>
     * </ul>
     */
    @GetMapping("/{id}/inspector")
    public Mono<InspectorDtos.InspectorSnapshot> inspect(@PathVariable("id") String id,
                                                         ServerWebExchange exchange) {
        return Mono.fromSupplier(() -> {
            SessionEntity row = lookup(id);
            if (row == null) throw ChatApiException.sessionNotFound();
            Ids.SessionId sessionId = new Ids.SessionId(id);
            boolean playground = !isIdentified(row.getPrincipalSubject())
                    || actAsRegistry.get(sessionId).isPresent();

            if (!operatorReaches(row.getTenantId(), exchange, playground ? Role.MEMBER : Role.ADMIN)) {
                boolean operator = OperatorContext.current(exchange).isPresent();
                if (playground) {
                    throw operator ? ChatApiException.sessionNotFound() : ChatApiException.authRequired();
                }
                // Not an operator with access: only the owner's own token is left.
                // An operator without access learns nothing, not even that it exists.
                try {
                    if (!admit(id, exchange, false).owned()) throw ChatApiException.sessionNotFound();
                } catch (ChatApiException refused) {
                    throw operator ? ChatApiException.sessionNotFound() : refused;
                }
            }
            return inspector.snapshot(sessionId);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * True when the request carries an operator (console cookie or API key,
     * bound by the operator filters on this one path) that reaches
     * {@code tenantId} at {@code minRole}.
     */
    private boolean operatorReaches(String tenantId, ServerWebExchange exchange, Role minRole) {
        if (OperatorContext.current(exchange).isEmpty()) return false;
        try {
            workspaceAccess.require(tenantId, exchange, minRole);
            return true;
        } catch (WorkspaceNotFoundException | ResponseStatusException denied) {
            return false;
        }
    }

    /* -------------------------------- usage -------------------------------- */

    /** What the caller's tenant has used of this month's allowance. */
    @GetMapping("/usage")
    public Mono<SessionDtos.UsageResponse> usage(@RequestParam("tenant") String tenantId,
                                                 ServerWebExchange exchange) {
        return Mono.fromSupplier(() -> {
            enforceOrigin(tenantId, exchange);
            requireIdentified(authenticate(authContext(new Ids.TenantId(tenantId), exchange)));
            UsageMeter.Totals totals = usage.usage(tenantId, null);
            return new SessionDtos.UsageResponse(totals.month(), totals.messages(), totals.monthlyMessages());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /* ------------------------------- admission ----------------------------- */

    /**
     * Who is calling an existing session. {@code owned} = the session was
     * started by an identified principal, and this caller proved to be that
     * same principal; otherwise it is an anonymous (playground) session.
     */
    private record Caller(AuthContext auth, SessionEntity row, Principal principal, Profile profile) {
        boolean owned() { return principal != null; }
    }

    private Caller admit(String id, ServerWebExchange exchange, boolean forTurn) {
        Ids.SessionId sessionId = new Ids.SessionId(id);
        SessionEntity row = lookup(id);
        Ids.TenantId tenant = new Ids.TenantId(row == null ? "default" : row.getTenantId());
        enforceOrigin(tenant.value(), exchange);
        // The conversation of a headless run (§17) is nobody's to continue,
        // read back or close through this API — not even its principal's.
        if (row != null && TurnChannel.isReserved(row.getChannel())) throw ChatApiException.sessionNotFound();
        AuthContext auth = actAsMaterializer.applyIfPresent(
                authContext(tenant, exchange), actAsRegistry.get(sessionId));

        if (row == null || !isIdentified(row.getPrincipalSubject())) {
            return new Caller(auth, row, null, row == null ? null : profileOf(row).orElse(null));
        }

        // The session has an owner. The token on THIS request must be valid now
        // and belong to that owner; anything else looks like "no such session".
        Principal principal = authenticate(auth);
        if (!row.getPrincipalSubject().equals(principal.subject())) {
            if (!isIdentified(principal.subject())) throw ChatApiException.authRequired();
            throw ChatApiException.sessionNotFound();
        }
        Profile profile = profileOf(row).orElse(null);
        if (forTurn) {
            if (row.getEndedAt() != null) {
                throw new ChatApiException(HttpStatus.CONFLICT, "session_ended",
                        "this conversation was closed; start a new one", 0);
            }
            if (isExpired(row, profile)) {
                throw new ChatApiException(HttpStatus.CONFLICT, "session_expired",
                        "this conversation is too old to continue; start a new one", 0);
            }
        }
        return new Caller(new AuthContext(tenant, auth.headers(), Optional.of(principal)),
                row, principal, profile);
    }

    /** Bring an owned session back into the engine after a restart dropped it. */
    private void rehydrate(Ids.SessionId sessionId, Caller caller) {
        if (!caller.owned()) return;
        try {
            engine.resume(sessionId, caller.auth().onChannel(TurnChannel.APP));
        } catch (IllegalStateException | ActionNotPermittedException | UnsupportedOperationException e) {
            throw ChatApiException.sessionNotFound();
        }
    }

    /** The engine does not hold this session (never existed, or dropped and not resumable). */
    private static boolean isUnknownSession(Throwable e) {
        return e instanceof IllegalStateException && e.getMessage() != null
                && e.getMessage().startsWith("unknown session");
    }

    private Principal authenticate(AuthContext auth) {
        try {
            return authenticator.authenticate(auth);
        } catch (AuthenticationException e) {
            String reason = e.getMessage() == null ? "" : e.getMessage().toLowerCase(java.util.Locale.ROOT);
            LOG.debug("session auth rejected: {}", e.getMessage());
            if (reason.contains("no authenticator recognised") || reason.contains("no credentials")) {
                throw ChatApiException.authRequired();
            }
            if (reason.contains("expired")) {
                throw new ChatApiException(HttpStatus.UNAUTHORIZED, "token_expired",
                        "the session token has expired", 0);
            }
            throw new ChatApiException(HttpStatus.UNAUTHORIZED, "token_invalid",
                    "the session token was not accepted", 0);
        }
    }

    private static Principal requireIdentified(Principal principal) {
        if (principal == null || !isIdentified(principal.subject())) throw ChatApiException.authRequired();
        return principal;
    }

    /** A real, verified subject — not the anonymous fallback and not a minted chat-anon principal. */
    private static boolean isIdentified(String subject) {
        return subject != null && !subject.isBlank()
                && !subject.startsWith("anonymous-") && !subject.contains(":anon:");
    }

    private void requireWithinLimit(String dimension, Caller caller) {
        requireWithinLimit(dimension, caller.row().getTenantId() + ":" + caller.principal().subject(),
                props.messagesPerMinuteOrDefault());
    }

    private void requireWithinLimit(String dimension, String who, int perMinute) {
        ChatRateLimiter.Decision decision;
        try {
            decision = rateLimiter.hit(dimension, who, perMinute, MINUTE);
        } catch (RuntimeException e) {
            // The limiter's store being down must not take the assistant down with it.
            LOG.warn("session rate limiter unavailable ({}); allowing", e.getClass().getSimpleName());
            return;
        }
        if (!decision.allowed()) throw ChatApiException.rateLimited(decision.retryAfterSeconds());
    }

    /* ------------------------------- streaming ----------------------------- */

    /**
     * An owned session's client reads structured errors: a refusal or a failed
     * turn ends the stream with one {@code error} event instead of a broken
     * connection. A playground session keeps its old behaviour, with the
     * allowance refusal spoken as text.
     */
    private Flux<ServerSentEvent<Object>> toSse(Flux<TurnEvent> turn, Caller caller) {
        if (!caller.owned()) {
            return QuotaRefusals.spoken(turn).map(webSse::toSse);
        }
        return turn.map(webSse::toSse)
                .onErrorResume(QuotaExceededException.class, e -> {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("code", QuotaExceededException.CODE);
                    data.put("message", QuotaRefusals.text(e.language()));
                    data.put("month", e.month());
                    data.put("messages", e.messages());
                    data.put("monthlyMessages", e.monthlyMessages());
                    return Flux.just(errorEvent(data));
                })
                .onErrorResume(e -> !(e instanceof ResponseStatusException)
                        && !(e instanceof ActionNotPermittedException), e -> {
                    LOG.warn("turn failed for session {}: {}", caller.row().getId(), e.toString());
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("code", "turn_failed");
                    data.put("message", "the assistant could not finish this turn");
                    return Flux.just(errorEvent(data));
                });
    }

    private static ServerSentEvent<Object> errorEvent(Map<String, Object> data) {
        return ServerSentEvent.builder((Object) data).event("error").build();
    }

    /* -------------------------------- history ------------------------------ */

    private List<SessionDtos.HistoryTurn> display(List<Turn> turns, Ids.TenantId tenant) {
        return display(turns, tenant, null);
    }

    /** {@code defaultChannel} labels turns stored before the channel was recorded on them. */
    @SuppressWarnings("unchecked")
    private List<SessionDtos.HistoryTurn> display(List<Turn> turns, Ids.TenantId tenant, String defaultChannel) {
        List<SessionDtos.HistoryTurn> out = new ArrayList<>();
        for (Turn t : turns) {
            if (t.role() != Turn.Role.USER && t.role() != Turn.Role.ASSISTANT) continue;
            String text = t.content() == null ? "" : t.content();
            List<Map<String, Object>> hints = new ArrayList<>();
            Object raw = t.metadata() == null ? null : t.metadata().get("uiHints");
            if (raw instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) hints.add(resign((Map<String, Object>) m, tenant));
                }
            }
            // A tool step with nothing to show, or a tap with no words.
            if (text.isBlank() && hints.isEmpty()) continue;
            String channel = t.metadata() != null && t.metadata().get("channel") instanceof String c && !c.isBlank()
                    ? c : defaultChannel;
            out.add(new SessionDtos.HistoryTurn(t.role().name().toLowerCase(java.util.Locale.ROOT), text,
                    t.createdAt() == null ? null : t.createdAt().toString(), hints, channel));
        }
        return out;
    }

    /**
     * The tool calls whose result was stored after the user's last stored
     * message — the current turn's, when one is running or was stopped. A
     * call appears here only once it finished, whatever became of the stream.
     */
    private static List<SessionDtos.ToolOutcome> lastToolResults(List<Turn> turns) {
        int from = 0;
        for (int i = turns.size() - 1; i >= 0; i--) {
            if (turns.get(i).role() == Turn.Role.USER) {
                from = i + 1;
                break;
            }
        }
        List<SessionDtos.ToolOutcome> out = new ArrayList<>();
        for (Turn t : turns.subList(from, turns.size())) {
            if (t.toolResults() == null) continue;
            for (Turn.ToolResult r : t.toolResults()) {
                // Told to wait for another card (§5, §16): it did not run.
                if (r.value() instanceof Map<?, ?> value && Boolean.FALSE.equals(value.get("ok"))
                        && "deferred".equals(value.get("reason"))) {
                    continue;
                }
                String tool = t.toolCalls() == null ? null : t.toolCalls().stream()
                        .filter(c -> c.callId() != null && c.callId().equals(r.callId()))
                        .map(c -> c.toolId().value()).findFirst().orElse(null);
                out.add(new SessionDtos.ToolOutcome(r.callId(), tool, r.status().name(),
                        t.createdAt() == null ? null : t.createdAt().toString()));
            }
        }
        return out;
    }

    /** Attachment links are short-lived grants; a reopened conversation gets fresh ones. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> resign(Map<String, Object> hint, Ids.TenantId tenant) {
        MediaTokenSigner signer = mediaTokens.getIfAvailable();
        if (signer == null || !"attachment".equals(hint.get("kind"))
                || !(hint.get("payload") instanceof Map<?, ?> payload)
                || payload.get("assetId") == null) {
            return hint;
        }
        try {
            Map<String, Object> fresh = new LinkedHashMap<>((Map<String, Object>) payload);
            fresh.put("url", "/v1/media/" + signer.mint(tenant, String.valueOf(payload.get("assetId"))));
            Map<String, Object> out = new LinkedHashMap<>(hint);
            out.put("payload", fresh);
            return out;
        } catch (RuntimeException e) {
            return hint;
        }
    }

    /** The confirm frame still waiting for an answer, exactly as it was streamed. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> pendingConfirm(Ids.SessionId sessionId) {
        try {
            Map<String, Object> wm = workingMemory.get(sessionId);
            if (wm != null && wm.get("pending_confirm") instanceof Map<?, ?> pending
                    && pending.get("frame") instanceof Map<?, ?> frame && !frame.isEmpty()) {
                return (Map<String, Object>) frame;
            }
        } catch (RuntimeException e) {
            LOG.debug("pending confirm unavailable for {}: {}", sessionId.value(), e.toString());
        }
        return null;
    }

    /**
     * The plan still waiting for an answer, exactly as it was streamed (§16).
     * Null once it was answered: a plan that is running, or whose run was cut
     * short, has no buttons to draw.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> pendingPlan(Ids.SessionId sessionId) {
        try {
            Map<String, Object> wm = workingMemory.get(sessionId);
            if (wm != null && wm.get("pending_plan") instanceof Map<?, ?> pending
                    && "PENDING".equals(pending.get("state"))
                    && pending.get("frame") instanceof Map<?, ?> frame && !frame.isEmpty()) {
                return (Map<String, Object>) frame;
            }
        } catch (RuntimeException e) {
            LOG.debug("pending plan unavailable for {}: {}", sessionId.value(), e.toString());
        }
        return null;
    }

    /**
     * The secret prompt still waiting for an answer: the frame that was
     * streamed, with the time left counted from now. Null once it lapsed.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> pendingSecret(Ids.SessionId sessionId) {
        try {
            Map<String, Object> wm = workingMemory.get(sessionId);
            if (wm != null && wm.get(SecretPrompts.SLOT) instanceof Map<?, ?> pending) {
                return SecretPrompts.view((Map<String, Object>) pending, Instant.now());
            }
        } catch (RuntimeException e) {
            LOG.debug("pending secret unavailable for {}: {}", sessionId.value(), e.toString());
        }
        return null;
    }

    /** {@code metadata.policy.sessionMaxAgeHours} on the profile bounds how long a session is continued. */
    private static boolean isExpired(SessionEntity row, Profile profile) {
        if (profile == null || profile.metadata() == null
                || !(profile.metadata().get("policy") instanceof Map<?, ?> policy)
                || !(policy.get("sessionMaxAgeHours") instanceof Number hours)
                || hours.doubleValue() <= 0) {
            return false;
        }
        Instant limit = row.getCreatedAt().toInstant()
                .plusSeconds((long) (hours.doubleValue() * 3600));
        return Instant.now().isAfter(limit);
    }

    /* -------------------------------- helpers ------------------------------ */

    private Optional<Profile> profileOf(SessionEntity row) {
        return profiles.find(new Ids.TenantId(row.getTenantId()),
                new Ids.ProfileId(row.getProfileId(), row.getProfileVersion()));
    }

    private void enforceOrigin(String tenantId, ServerWebExchange exchange) {
        originEnforcer.requirePermitted(tenantId,
                exchange.getRequest().getHeaders().getFirst("Origin"),
                exchange.getRequest().getPath().value(), null);
    }

    private SessionEntity lookup(String sessionId) {
        try {
            return sessions.findById(UUID.fromString(sessionId)).orElse(null);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private AuthContext authContext(Ids.TenantId tenant, ServerWebExchange exchange) {
        Map<String, String> headers = exchange.getRequest().getHeaders().toSingleValueMap();
        return new AuthContext(tenant, headers, Optional.empty());
    }
}
