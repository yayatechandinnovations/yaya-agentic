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
import com.yayatechandinnovations.yayaagentic.channel.chat.media.ChatAttachmentService;
import com.yayatechandinnovations.yayaagentic.channel.chat.media.ChatMessageComposer;
import com.yayatechandinnovations.yayaagentic.channel.chat.receipts.ChatReceiptService;
import com.yayatechandinnovations.yayaagentic.channel.chat.receipts.ReceiptTemplateService;
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
    private final ChatAttachmentService chatAttachments;
    private final ReceiptTemplateService receiptTemplates;
    private final ChatReceiptService chatReceipts;

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
                                ConversationRecorder recorder,
                                ChatAttachmentService chatAttachments,
                                ReceiptTemplateService receiptTemplates,
                                ChatReceiptService chatReceipts) {
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
        this.chatAttachments = chatAttachments;
        this.receiptTemplates = receiptTemplates;
        this.chatReceipts = chatReceipts;
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
                    resolveTraits(ep, result.clientTraits()));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Traits flow verbatim from {@code Profile.extensions} — except
     * {@code chat.receipts} (chat-experience-design.md §7.3), whose template
     * ids are resolved into full layout payloads here so the client has every
     * layout at session start. The framework still interprets nothing inside
     * the templates; it just joins ids to rows.
     */
    private Map<String, Object> resolveTraits(ChatEntryPoint ep, Map<String, Object> traits) {
        if (traits == null || !traits.containsKey(ReceiptTemplateService.TRAIT_KIND)) return traits;
        Map<String, Object> out = new HashMap<>(traits);
        Map<String, Object> resolved = receiptTemplates.resolveTrait(
                ep.tenant(), traits.get(ReceiptTemplateService.TRAIT_KIND));
        if (resolved == null) {
            out.remove(ReceiptTemplateService.TRAIT_KIND);
        } else {
            out.put(ReceiptTemplateService.TRAIT_KIND, resolved);
        }
        return out;
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
            // Wallet capture (§7.5): action-emitted receipts persist too — a
            // "Buy ticket" tap is the most common issuance path.
            final java.util.UUID walletAccount =
                    sessionLinks.accountOf(java.util.UUID.fromString(id)).orElse(null);
            return engine.dispatchAction(sessionId, new Ids.ToolId(body.tool()),
                            body.args() == null ? Map.of() : body.args(), auth)
                    .doOnNext(evt -> chatReceipts.maybePersist(
                            evt, ep.tenant(), id, ctx.handle(), walletAccount))
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

            // Attachments (chat-experience-design.md §6.3): resolve the referenced
            // uploads (session-scoped, single-use) and compose the engine-facing
            // message — transcripts as speech, doc text delimited as data,
            // images as vision parts.
            UserMessage um;
            List<String> attachmentIds = List.of();
            if (body.attachmentIds() != null && !body.attachmentIds().isEmpty()) {
                try {
                    var atts = chatAttachments.resolveForSend(ep.tenant(), id, body.attachmentIds());
                    attachmentIds = atts.stream()
                            .map(ChatAttachmentService.ChatAttachment::id).toList();
                    um = ChatMessageComposer.compose(body.text(), atts,
                            aid -> chatAttachments.readBytes(ep.tenant(), aid).orElse(null));
                } catch (ChatAttachmentService.RejectedException e) {
                    throw toApi(e);
                }
            } else {
                um = new UserMessage(body.text(), Map.of());
            }
            // Structured selection (network chat design §3.2): rides a
            // framework-owned "_selection" key in the engine-facing attachments
            // map so ConversationEngine.send / UserMessage keep their signatures.
            // The engine reads it BEFORE NL resolution; validation against the
            // last emitted picker happens server-side.
            if (body.selection() != null && body.selection().kind() != null) {
                var sel = body.selection();
                java.util.Map<String, Object> selMap = new java.util.LinkedHashMap<>();
                selMap.put("kind", sel.kind());
                if (sel.code() != null) selMap.put("code", sel.code());
                if (sel.callId() != null) selMap.put("callId", sel.callId());
                java.util.Map<String, Object> attrs = new java.util.LinkedHashMap<>(um.attachments());
                attrs.put("_selection", selMap);
                um = new UserMessage(um.text(), attrs);
            }
            final List<String> sentIds = attachmentIds;
            // Wallet capture (§7.5): receipt hints on a verified session persist.
            final java.util.UUID walletAccount =
                    sessionLinks.accountOf(java.util.UUID.fromString(id)).orElse(null);
            return engine.send(sessionId, um, auth)
                    .doOnNext(evt -> chatReceipts.maybePersist(
                            evt, ep.tenant(), id, ctx.handle(), walletAccount))
                    .map(chatAdapter::toSse)
                    // Single-use: once the turn streamed to completion the
                    // attachments belong to it; an errored send leaves them
                    // unreferenced for the orphan sweep.
                    .doOnComplete(() -> {
                        if (!sentIds.isEmpty()) chatAttachments.markReferenced(sentIds, "sent");
                    });
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /* --------------------------- attachments (§6) -------------------------- */

    // §6.2 ceilings — counts here, byte budgets + size caps in the service.
    private static final int UPLOADS_PER_SESSION_PER_HOUR = 20;
    private static final int UPLOADS_PER_ACCOUNT_PER_DAY = 40;
    /** Hard multipart read bound: the largest per-kind cap, plus form overhead. */
    private static final int MAX_UPLOAD_READ_BYTES = 16 * 1024 * 1024;

    @PostMapping(value = "/sessions/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<PublicChatDtos.AttachmentResponse> uploadAttachment(
            @PathVariable("id") String id,
            @RequestPart("file") Mono<org.springframework.http.codec.multipart.FilePart> file,
            ServerWebExchange exchange) {
        return file.flatMap(part ->
                org.springframework.core.io.buffer.DataBufferUtils.join(part.content(), MAX_UPLOAD_READ_BYTES)
                        .map(buf -> {
                            byte[] bytes = new byte[buf.readableByteCount()];
                            buf.read(bytes);
                            org.springframework.core.io.buffer.DataBufferUtils.release(buf);
                            return bytes;
                        })
                        .publishOn(Schedulers.boundedElastic())
                        .map(bytes -> {
                            ChatSessionRegistry.Context ctx = registry.get(new Ids.SessionId(id))
                                    .orElseThrow(ChatApiException::sessionNotFound);
                            ChatEntryPoint ep = entryPoints.findByHandle(ctx.handle())
                                    .orElseThrow(ChatApiException::sessionNotFound);

                            // L1 floor (§6.2): media needs a verified, account-linked
                            // caller — identity is the abuse brake — and the session
                            // must be the caller's own.
                            VerifiedDevice device = caller(exchange).device()
                                    .filter(d -> d.accountId() != null)
                                    .orElseThrow(() -> new ChatApiException(
                                            org.springframework.http.HttpStatus.FORBIDDEN,
                                            "verification_required",
                                            "verify your phone number to send media", 0));
                            java.util.UUID account = java.util.UUID.fromString(device.accountId());
                            java.util.UUID owner = sessionLinks
                                    .accountOf(java.util.UUID.fromString(id)).orElse(null);
                            if (owner == null || !owner.equals(account)) {
                                throw ChatApiException.sessionNotFound();
                            }

                            requireWithinLimit(rateLimiter.hit("att-sess", id,
                                    UPLOADS_PER_SESSION_PER_HOUR, HOUR));
                            requireWithinLimit(rateLimiter.hit("att-acct-day", account.toString(),
                                    UPLOADS_PER_ACCOUNT_PER_DAY, DAY));

                            try {
                                var att = chatAttachments.upload(ep.tenant(), id, account,
                                        part.filename(), bytes);
                                return new PublicChatDtos.AttachmentResponse(att.id(), att.kind(),
                                        att.contentType(), att.filename(), att.sizeBytes(),
                                        att.signedUrl(), att.transcript());
                            } catch (ChatAttachmentService.RejectedException e) {
                                throw toApi(e);
                            }
                        }))
                .onErrorMap(org.springframework.core.io.buffer.DataBufferLimitException.class,
                        e -> new ChatApiException(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,
                                "too_large", "this file is too large", 0));
    }

    /** Streams attachment bytes behind a verified signed token — the chat
     *  mirror of {@code GET /v1/media/{token}}. */
    @GetMapping("/media/{token}")
    public Mono<org.springframework.http.ResponseEntity<org.springframework.core.io.Resource>> media(
            @PathVariable("token") String token) {
        return Mono.fromSupplier(() -> chatAttachments.openSigned(token)
                        .map(s -> org.springframework.http.ResponseEntity.ok()
                                .header(org.springframework.http.HttpHeaders.CONTENT_TYPE, s.contentType())
                                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                                        "inline; filename=\"" + s.filename().replace("\"", "") + "\"")
                                .contentLength(s.sizeBytes())
                                .<org.springframework.core.io.Resource>body(
                                        new org.springframework.core.io.InputStreamResource(s.stream())))
                        .orElseGet(() -> org.springframework.http.ResponseEntity.notFound().build()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static ChatApiException toApi(ChatAttachmentService.RejectedException e) {
        org.springframework.http.HttpStatus status = switch (e.code()) {
            case "too_large" -> org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE;
            case "quota_exceeded" -> org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;
            case "not_found" -> org.springframework.http.HttpStatus.NOT_FOUND;
            default -> org.springframework.http.HttpStatus.BAD_REQUEST;
        };
        return new ChatApiException(status, e.code(), e.getMessage(), 0);
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
            List<PublicChatDtos.HistoryTurn> turns = history ? hydrate(sessionId, ep, exchange) : null;
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
    private List<PublicChatDtos.HistoryTurn> hydrate(Ids.SessionId sessionId, ChatEntryPoint ep,
                                                     ServerWebExchange exchange) {
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
                .map(t -> new PublicChatDtos.HistoryTurn(t.role().name().toLowerCase(), t.content(),
                        attachmentViews(t, ep)))
                .toList();
    }

    /** Re-render media bubbles from the turn's recorded attachment metadata
     *  (§6.4) with freshly signed URLs — the old grants have long expired. */
    private List<PublicChatDtos.AttachmentView> attachmentViews(Turn t, ChatEntryPoint ep) {
        Object raw = t.metadata() == null ? null : t.metadata().get("attachments");
        if (!(raw instanceof List<?> list) || list.isEmpty()) return List.of();
        return list.stream()
                .filter(o -> o instanceof Map<?, ?>)
                .map(o -> (Map<?, ?>) o)
                .map(m -> new PublicChatDtos.AttachmentView(
                        String.valueOf(m.get("id")),
                        String.valueOf(m.get("kind")),
                        String.valueOf(m.get("contentType")),
                        String.valueOf(m.get("filename")),
                        chatAttachments.signedUrl(ep.tenant(), String.valueOf(m.get("id")))))
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
