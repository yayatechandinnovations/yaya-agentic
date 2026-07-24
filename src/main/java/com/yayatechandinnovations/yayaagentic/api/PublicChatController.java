package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.PublicChatDtos;
import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.auth.Authenticator;
import com.yayatechandinnovations.yayaagentic.channel.auth.ChannelAuthTrace;
import com.yayatechandinnovations.yayaagentic.channel.chat.AuthMode;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatAuthService;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatChannelAdapter;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatEntryPoint;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatEntryPointService;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatRateLimiter;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatSessionLinkService;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatSessionRegistry;
import com.yayatechandinnovations.yayaagentic.channel.chat.EntryPointStatus;
import com.yayatechandinnovations.yayaagentic.channel.chat.verify.ChatAccountService;
import com.yayatechandinnovations.yayaagentic.channel.chat.verify.DevicePassService;
import com.yayatechandinnovations.yayaagentic.channel.chat.verify.VerifiedDevice;
import com.yayatechandinnovations.yayaagentic.channel.auth.SessionAuthTokenStore;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.engine.ActionNotPermittedException;
import com.yayatechandinnovations.yayaagentic.engine.ConversationEngine;
import com.yayatechandinnovations.yayaagentic.engine.StartSessionResult;
import com.yayatechandinnovations.yayaagentic.engine.UserMessage;
import com.yayatechandinnovations.yayaagentic.core.Turn;
import com.yayatechandinnovations.yayaagentic.persistence.SessionEntity;
import com.yayatechandinnovations.yayaagentic.profile.StartConversationRequest;
import com.yayatechandinnovations.yayaagentic.recorder.ConversationRecorder;
import com.yayatechandinnovations.yayaagentic.recorder.TurnRange;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The public, unauthenticated-capable chat front door ({@code chat-channel-design.md} §9).
 * Deliberately separate from {@code /v1/sessions/*} (the operator playground) and
 * {@code /v1/admin/*} (operator config): it is entry-point-scoped, abuse-gated,
 * and mints its principal from the entry point's auth mode rather than from
 * operator auth. {@code /v1/chat/**} is public by default (not in the operator
 * auth filter's protected set).
 *
 * <p>Identity resolution + the auth-flow trace live in {@link ChatAuthService},
 * shared verbatim with the operator chat playground so the two can never drift.
 */
@RestController
@RequestMapping("/v1/chat")
public class PublicChatController {

    private static final Duration HOUR = Duration.ofHours(1);
    private static final Duration DAY = Duration.ofHours(24);

    private final ConversationEngine engine;
    private final ChatEntryPointService entryPoints;
    private final ChatSessionRegistry registry;
    private final ChatRateLimiter rateLimiter;
    private final ChatChannelAdapter chatAdapter;
    private final Authenticator authenticator;
    private final DevicePassService devicePasses;
    private final ChatAccountService accounts;
    private final ChatAuthService chatAuth;
    private final SessionAuthTokenStore authTokenStore;
    private final ChatSessionLinkService sessionLinks;
    private final ConversationRecorder recorder;

    public PublicChatController(ConversationEngine engine,
                                ChatEntryPointService entryPoints,
                                ChatSessionRegistry registry,
                                ChatRateLimiter rateLimiter,
                                ChatChannelAdapter chatAdapter,
                                Authenticator authenticator,
                                DevicePassService devicePasses,
                                ChatAccountService accounts,
                                ChatAuthService chatAuth,
                                SessionAuthTokenStore authTokenStore,
                                ChatSessionLinkService sessionLinks,
                                ConversationRecorder recorder) {
        this.engine = engine;
        this.entryPoints = entryPoints;
        this.registry = registry;
        this.rateLimiter = rateLimiter;
        this.chatAdapter = chatAdapter;
        this.authenticator = authenticator;
        this.devicePasses = devicePasses;
        this.accounts = accounts;
        this.chatAuth = chatAuth;
        this.authTokenStore = authTokenStore;
        this.sessionLinks = sessionLinks;
        this.recorder = recorder;
    }

    /* -------------------------- handle resolution ------------------------- */

    @GetMapping("/e/{handle}")
    public Mono<PublicChatDtos.EntryPointCard> resolve(@PathVariable("handle") String handle) {
        return Mono.fromSupplier(() -> PublicChatDtos.EntryPointCard.of(requirePublic(handle)));
    }

    /* ----------------------------- start ---------------------------------- */

    @PostMapping("/e/{handle}/sessions")
    public Mono<PublicChatDtos.StartResponse> start(@PathVariable("handle") String handle,
                                                    @RequestBody(required = false) PublicChatDtos.StartRequest body,
                                                    ServerWebExchange exchange) {
        return Mono.fromSupplier(() -> {
            ChatEntryPoint ep = requirePublic(handle);
            if (!ep.status().servesSessions()) {
                // PAUSED resolves (so the app can show "temporarily unavailable") but
                // never starts a session.
                throw ChatApiException.unknownHandle();
            }
            String deviceId = body == null ? null : body.deviceId();
            String locale = body != null && body.locale() != null ? body.locale() : ep.branding().defaultLocale();

            // Front-door abuse gate (§7.1) — per device + per IP, before any LLM round-trip.
            requireWithinLimit(rateLimiter.hit("start-dev", handle + ":" + safe(deviceId),
                    ep.abuse().startsPerDevicePerHour(), HOUR));
            requireWithinLimit(rateLimiter.hit("start-ip", handle + ":" + clientIp(exchange),
                    ep.abuse().startsPerIpPerHour(), HOUR));

            ChatAuthService.Caller theCaller = caller(exchange);
            ChatAuthService.Resolution res = chatAuth.resolveForStart(ep, handle, deviceId, theCaller);
            if (res.rejected()) throw ChatApiException.resolverUnresolved();
            AuthContext auth = res.auth();

            Map<String, Object> hints = new HashMap<>();
            hints.put("channel.kind", "chat");
            hints.put("channel.entryPoint", ep.id().value());
            hints.put("channel.handle", handle);
            hints.put("channel.deviceId", safe(deviceId));
            hints.put("channel.locale", locale);
            hints.put("channel.authMode", ep.authMode().name());
            // Session Start Flow binding (docs/design/session-start-flows.md §6):
            // channel binding wins over the profile default in the resolver.
            if (ep.startFlowId() != null && !ep.startFlowId().isBlank()) {
                hints.put("channel.startFlowId", ep.startFlowId());
            }

            StartConversationRequest req = new StartConversationRequest(
                    ep.tenant(), Optional.empty(), "chat", hints);
            StartSessionResult result = engine.start(req, auth);
            Ids.SessionId sid = result.session().id();

            // Resolver-exchange: persist the minted JWT on the fresh session row so
            // the engine can forward it to PRINCIPAL_TOKEN tools and refresh it on a
            // tool-level 401 — mirrors the WhatsApp InboundDispatchWorker step.
            res.jwt().ifPresent(jwt -> authTokenStore.persist(sid, jwt));
            chatAuth.recordTrace(sid, ep, res, res.jwt().isPresent()
                    ? ChannelAuthTrace.TokenState.STORED : ChannelAuthTrace.TokenState.ABSENT);

            registry.register(sid,
                    new ChatSessionRegistry.Context(ep.tenant().value(), ep.id().value(),
                            handle, deviceId, ep.authMode().name(), res.startedAuthenticated()),
                    ep.sessionPolicy().idleTimeout());

            // Link the session to its L1 account so it can be found for resume
            // across restart / reinstall / phone change (§5.3, §7.2).
            theCaller.device()
                    .filter(d -> d.accountId() != null)
                    .ifPresent(d -> sessionLinks.link(java.util.UUID.fromString(sid.value()),
                            java.util.UUID.fromString(d.accountId()), ep.id().value()));

            return new PublicChatDtos.StartResponse(
                    sid.value(),
                    result.profile().value(),
                    result.profile().version(),
                    result.greeting(),
                    result.quickReplies(),
                    PublicChatDtos.EntryPointCard.of(ep),
                    result.clientTraits());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /* ---------------------------- action (tap) ---------------------------- */

    /**
     * The client-initiated action channel (chat-channel-design.md): a tap on a
     * rendered affordance runs ONE whitelisted, {@code clientInvocable} tool
     * through the exact same {@code ToolExecutor} + Authorizer as a chat message,
     * streaming back the same SSE shape (ToolResult + any UiHint the tool emits).
     * "Two doors, one lock" — a tap is an untrusted proposal, not a shortcut. A
     * refused action (tool not in profile / not clientInvocable) collapses to a
     * bare 403; the tool's existence is never leaked. Mirrors the abuse gate +
     * auth resolution of {@link #sendMessage}.
     */
    @PostMapping(value = "/sessions/{id}/actions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> action(@PathVariable("id") String id,
                                                @RequestBody PublicChatDtos.ActionRequest body,
                                                ServerWebExchange exchange) {
        Ids.SessionId sessionId = new Ids.SessionId(id);
        return Flux.defer(() -> {
            ChatSessionRegistry.Context ctx = registry.get(sessionId)
                    .orElseThrow(ChatApiException::sessionNotFound);
            ChatEntryPoint ep = entryPoints.findByHandle(ctx.handle())
                    .orElseThrow(ChatApiException::sessionNotFound);

            // A tap is an LLM-free turn, but still a turn: same per-session gate as
            // a message (it can trigger a merchant HTTP call), and the anonymous
            // daily cap so taps can't bypass the free-session circuit breaker.
            requireWithinLimit(rateLimiter.hit("msg-sess", id,
                    ep.abuse().messagesPerSessionPerHour(), HOUR));
            if (isAnonymous(ctx, exchange)) {
                requireWithinLimit(rateLimiter.hit("msg-anon-day",
                        ctx.handle() + ":" + safe(ctx.deviceId()),
                        ep.abuse().anonymousDailyMessageCap(), DAY));
            }

            AuthContext auth = authForExisting(ctx, ep, exchange, sessionId);
            registry.touch(sessionId, ep.sessionPolicy().idleTimeout());
            return engine.dispatchAction(sessionId, new Ids.ToolId(body.tool()),
                            body.args() == null ? Map.of() : body.args(), auth)
                    .map(chatAdapter::toSse);
        }).subscribeOn(Schedulers.boundedElastic())
          .onErrorMap(ActionNotPermittedException.class,
                  e -> new org.springframework.web.server.ResponseStatusException(
                          org.springframework.http.HttpStatus.FORBIDDEN));
    }

    /* ----------------------------- send ----------------------------------- */

    @PostMapping(value = "/sessions/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> sendMessage(@PathVariable("id") String id,
                                                     @RequestBody PublicChatDtos.MessageRequest body,
                                                     ServerWebExchange exchange) {
        Ids.SessionId sessionId = new Ids.SessionId(id);
        return Flux.defer(() -> {
            ChatSessionRegistry.Context ctx = registry.get(sessionId)
                    .orElseThrow(ChatApiException::sessionNotFound);
            ChatEntryPoint ep = entryPoints.findByHandle(ctx.handle())
                    .orElseThrow(ChatApiException::sessionNotFound);

            requireWithinLimit(rateLimiter.hit("msg-sess", id,
                    ep.abuse().messagesPerSessionPerHour(), HOUR));
            // The LLM-cost circuit breaker: anonymous sessions are free to the user.
            if (isAnonymous(ctx, exchange)) {
                requireWithinLimit(rateLimiter.hit("msg-anon-day",
                        ctx.handle() + ":" + safe(ctx.deviceId()),
                        ep.abuse().anonymousDailyMessageCap(), DAY));
            }

            AuthContext auth = authForExisting(ctx, ep, exchange, sessionId);
            registry.touch(sessionId, ep.sessionPolicy().idleTimeout());
            return engine.send(sessionId, new UserMessage(body.text(), Map.of()), auth)
                    .map(chatAdapter::toSse);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /* --------------------------- resume / end ----------------------------- */

    @GetMapping("/sessions/{id}")
    public Mono<PublicChatDtos.ResumeResponse> resume(@PathVariable("id") String id,
                                                      @RequestParam(value = "history", defaultValue = "false") boolean history,
                                                      ServerWebExchange exchange) {
        Ids.SessionId sessionId = new Ids.SessionId(id);
        return Mono.fromSupplier(() -> {
            ChatSessionRegistry.Context ctx = registry.get(sessionId)
                    .orElseThrow(ChatApiException::sessionNotFound);
            ChatEntryPoint ep = entryPoints.findByHandle(ctx.handle())
                    .orElseThrow(ChatApiException::sessionNotFound);
            List<PublicChatDtos.HistoryTurn> turns = history ? hydrate(sessionId, exchange) : null;
            return new PublicChatDtos.ResumeResponse(id, true, PublicChatDtos.EntryPointCard.of(ep), turns);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** The resume candidate for a returning verified user (§7.2): the most recent
     *  session this account held at this chat, classified LIVE / ENDED / NONE. */
    @GetMapping("/e/{handle}/sessions/latest")
    public Mono<PublicChatDtos.LatestSessionResponse> latestSession(@PathVariable("handle") String handle,
                                                                    ServerWebExchange exchange) {
        return Mono.fromSupplier(() -> {
            ChatEntryPoint ep = requirePublic(handle);
            Optional<VerifiedDevice> device = caller(exchange).device();
            String accountId = device.map(VerifiedDevice::accountId).orElse(null);
            if (accountId == null) return PublicChatDtos.LatestSessionResponse.none();

            Optional<SessionEntity> latest = sessionLinks.latest(
                    java.util.UUID.fromString(accountId), ep.id().value());
            if (latest.isEmpty()) return PublicChatDtos.LatestSessionResponse.none();

            SessionEntity s = latest.get();
            List<Turn> turns = recorder.loadTurns(new Ids.SessionId(s.getId().toString()), TurnRange.all());
            java.time.Instant lastActivity = turns.isEmpty()
                    ? s.getCreatedAt().toInstant()
                    : turns.get(turns.size() - 1).createdAt();
            boolean live = s.getEndedAt() == null
                    && java.time.Duration.between(lastActivity, java.time.Instant.now())
                            .compareTo(ep.sessionPolicy().idleTimeout()) < 0;
            String preview = turns.isEmpty() ? null : turns.get(turns.size() - 1).content();
            return new PublicChatDtos.LatestSessionResponse(
                    live ? "LIVE" : "ENDED", s.getId().toString(),
                    lastActivity.toString(), turns.size(), preview);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** Load the display transcript for a resumed session, enforcing account
     *  ownership: an account-linked session may be read only by its owner (§7.2) —
     *  a mismatch is 404, never leaking existence. Anonymous (unlinked) sessions
     *  stay capability-scoped by their unguessable id, as before. */
    private List<PublicChatDtos.HistoryTurn> hydrate(Ids.SessionId sessionId, ServerWebExchange exchange) {
        java.util.UUID sid = java.util.UUID.fromString(sessionId.value());
        java.util.UUID owner = sessionLinks.accountOf(sid).orElse(null);
        if (owner != null) {
            String callerAccount = caller(exchange).device().map(VerifiedDevice::accountId).orElse(null);
            if (callerAccount == null || !owner.equals(java.util.UUID.fromString(callerAccount))) {
                throw ChatApiException.sessionNotFound();
            }
        }
        return recorder.loadTurns(sessionId, TurnRange.all()).stream()
                .filter(t -> t.role() == Turn.Role.USER || t.role() == Turn.Role.ASSISTANT)
                .map(t -> new PublicChatDtos.HistoryTurn(t.role().name().toLowerCase(), t.content()))
                .toList();
    }

    /** AUTH_OPTIONAL mid-session upgrade (§8.4): verify the presented bearer and,
     *  on success, re-bind the session to the verified identity so subsequent
     *  messages run authenticated. ANONYMOUS_ONLY entry points reject upgrades. */
    @PostMapping("/sessions/{id}/authenticate")
    public Mono<PublicChatDtos.UpgradeResponse> authenticate(@PathVariable("id") String id,
                                                             ServerWebExchange exchange) {
        Ids.SessionId sessionId = new Ids.SessionId(id);
        return Mono.fromSupplier(() -> {
            ChatSessionRegistry.Context ctx = registry.get(sessionId)
                    .orElseThrow(ChatApiException::sessionNotFound);
            ChatEntryPoint ep = entryPoints.findByHandle(ctx.handle())
                    .orElseThrow(ChatApiException::sessionNotFound);
            if (ep.authMode() == AuthMode.ANONYMOUS_ONLY) {
                throw new ChatApiException(org.springframework.http.HttpStatus.CONFLICT,
                        "auth_not_supported", "this chat does not support sign-in", 0);
            }
            if (bearer(exchange) == null) throw ChatApiException.authRequired();

            Principal verified = authenticator.tryAuthenticate(verifiedContext(ep, exchange))
                    .filter(p -> !isAnonymousSubject(p))
                    .orElseThrow(ChatApiException::authRequired);

            registry.markAuthenticated(sessionId);
            registry.touch(sessionId, ep.sessionPolicy().idleTimeout());
            return new PublicChatDtos.UpgradeResponse(true, verified.subject());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/sessions/{id}/end")
    public Mono<Void> end(@PathVariable("id") String id, ServerWebExchange exchange) {
        Ids.SessionId sessionId = new Ids.SessionId(id);
        return Mono.<Void>fromRunnable(() -> {
            registry.get(sessionId).ifPresent(ctx -> {
                entryPoints.findByHandle(ctx.handle()).ifPresent(ep ->
                        engine.end(sessionId, authForExisting(ctx, ep, exchange, sessionId)));
                registry.remove(sessionId);
                chatAuth.wipeTrace(sessionId);
            });
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /* ------------------------------ helpers ------------------------------- */

    /** Resolve a handle for public surfaces. ACTIVE and PAUSED resolve; DRAFT,
     *  DEACTIVATED, and unknown all collapse to one opaque 404 (§6.1). */
    private ChatEntryPoint requirePublic(String handle) {
        ChatEntryPoint ep = entryPoints.findByHandle(handle).orElseThrow(ChatApiException::unknownHandle);
        if (ep.status() != EntryPointStatus.ACTIVE && ep.status() != EntryPointStatus.PAUSED) {
            throw ChatApiException.unknownHandle();
        }
        return ep;
    }

    /** Re-resolve identity for an existing session and refresh the inspector
     *  trace. Resolver-exchange sessions never carry a client bearer — the caller
     *  keeps sending only its device pass; the re-resolve is a cache HIT in the
     *  common case and also refreshes the forwardable token for tool calls. */
    private AuthContext authForExisting(ChatSessionRegistry.Context ctx, ChatEntryPoint ep,
                                        ServerWebExchange exchange, Ids.SessionId sid) {
        ChatAuthService.Resolution res = chatAuth.resolveForExisting(
                ep, ctx.handle(), ctx.deviceId(), ctx.authenticated(), caller(exchange));
        if (res.rejected()) throw ChatApiException.resolverUnresolved();
        chatAuth.recordTrace(sid, ep, res, res.jwt().isPresent()
                ? ChannelAuthTrace.TokenState.STORED : ChannelAuthTrace.TokenState.ABSENT);
        return res.auth();
    }

    /** Build the transport-independent {@link ChatAuthService.Caller} from the
     *  request: the verified device pass (L1) and/or the tenant bearer (L2). The
     *  device pass rides a dedicated {@code X-Device-Pass} header so it never
     *  collides with an {@code Authorization} tenant bearer (§20.1). */
    private ChatAuthService.Caller caller(ServerWebExchange exchange) {
        // A signature-valid pass is honoured only while its generation still
        // matches the account — drops revoked passes (delete / phone change /
        // sign-out-everywhere; §5.2).
        Optional<VerifiedDevice> device = devicePasses.verify(
                        exchange.getRequest().getHeaders().getFirst("X-Device-Pass"))
                .filter(accounts::isLive);
        return new ChatAuthService.Caller(device, Optional.ofNullable(bearer(exchange)),
                exchange.getRequest().getHeaders().toSingleValueMap());
    }

    /** Anonymous unless the mode forbids it, the session was upgraded, or a
     *  bearer is presented. Used only for the anonymous-daily-cap rate gate. */
    private boolean isAnonymous(ChatSessionRegistry.Context ctx, ServerWebExchange exchange) {
        if (ctx.authenticated()) return false; // upgraded via /authenticate (§8.4)
        AuthMode mode = AuthMode.parse(ctx.authMode());
        if (mode == AuthMode.ANONYMOUS_ONLY) return true;
        if (mode == AuthMode.AUTH_REQUIRED) return false;
        return bearer(exchange) == null; // AUTH_OPTIONAL: anon until a bearer appears
    }

    /** Let the authenticator chain verify the bearer (OIDC / shared-secret) against
     *  the entry point's tenant trust — no chat-specific verification. */
    private AuthContext verifiedContext(ChatEntryPoint ep, ServerWebExchange exchange) {
        Map<String, String> headers = exchange.getRequest().getHeaders().toSingleValueMap();
        return new AuthContext(ep.tenant(), headers, Optional.empty());
    }

    private void requireWithinLimit(ChatRateLimiter.Decision d) {
        if (!d.allowed()) throw ChatApiException.rateLimited(d.retryAfterSeconds());
    }

    /** Reject the dev anonymous fallback ({@code anonymous-…}) and any minted
     *  chat-anon principal ({@code …:anon:…}) — an upgrade requires a real
     *  verified identity, not the allow-anonymous safety net. */
    private static boolean isAnonymousSubject(Principal p) {
        String s = p.subject();
        return s == null || s.startsWith("anonymous-") || s.contains(":anon:");
    }

    private static String bearer(ServerWebExchange exchange) {
        String h = exchange.getRequest().getHeaders().getFirst("Authorization");
        return (h != null && h.regionMatches(true, 0, "Bearer ", 0, 7)) ? h.substring(7).trim() : null;
    }

    private static String clientIp(ServerWebExchange exchange) {
        String xff = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return exchange.getRequest().getRemoteAddress() == null
                ? "unknown"
                : exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
    }

    private static String safe(String s) {
        return s == null || s.isBlank() ? "nodev" : s.trim();
    }
}
