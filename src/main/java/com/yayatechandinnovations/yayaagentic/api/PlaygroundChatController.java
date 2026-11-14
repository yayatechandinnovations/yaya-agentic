package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.core.TurnChannel;
import com.yayatechandinnovations.yayaagentic.api.dto.InspectorDtos;
import com.yayatechandinnovations.yayaagentic.api.dto.PublicChatDtos;
import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.channel.auth.ChannelAuthTrace;
import com.yayatechandinnovations.yayaagentic.channel.auth.SessionAuthTokenStore;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatAuthService;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatChannelAdapter;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatEntryPoint;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatEntryPointId;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatEntryPointService;
import com.yayatechandinnovations.yayaagentic.channel.chat.playground.PlaygroundChatSessions;
import com.yayatechandinnovations.yayaagentic.channel.chat.verify.VerifiedDevice;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.engine.ActionNotPermittedException;
import com.yayatechandinnovations.yayaagentic.engine.ConversationEngine;
import com.yayatechandinnovations.yayaagentic.engine.StartSessionResult;
import com.yayatechandinnovations.yayaagentic.engine.UserMessage;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceAccessHelper;
import com.yayatechandinnovations.yayaagentic.profile.StartConversationRequest;
import com.yayatechandinnovations.yayaagentic.tenant.TenantGuard;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Operator-facing chat playground: exercise a chat entry point end-to-end
 * through the exact same {@link ChatAuthService} + {@link ConversationEngine}
 * path the public front door uses, while simulating the caller identity
 * (anonymous / phone-verified / tenant-bearer) server-side — no Twilio Verify,
 * no public abuse throttle. The auth-flow inspector reads the same {@link
 * InspectorService} snapshot as the WhatsApp and SSE playgrounds, so a failing
 * resolver-exchange shows its request/response and the "where did it fail?"
 * hints without a code change on the tenant side.
 *
 * <p>Mounted under {@code /v1/admin/**} so the {@code OperatorAuthWebFilter}
 * populates the operator context from the {@code YAYA_SESSION} cookie;
 * {@code workspaceAccess.require} then gates on tenant membership like every
 * other admin-tier controller. Unlike the public door this intentionally does
 * NOT require the entry point to be ACTIVE — the whole point is to test a DRAFT
 * before publishing.
 */
@RestController
@RequestMapping("/v1/admin/tenants/{tenantId}/chat-playground")
public class PlaygroundChatController {

    private final TenantGuard tenantGuard;
    private final WorkspaceAccessHelper workspaceAccess;
    private final ConversationEngine engine;
    private final ChatEntryPointService entryPoints;
    private final ChatAuthService chatAuth;
    private final ChatChannelAdapter chatAdapter;
    private final SessionAuthTokenStore authTokenStore;
    private final InspectorService inspector;
    private final PlaygroundChatSessions sessions;

    public PlaygroundChatController(TenantGuard tenantGuard,
                                    WorkspaceAccessHelper workspaceAccess,
                                    ConversationEngine engine,
                                    ChatEntryPointService entryPoints,
                                    ChatAuthService chatAuth,
                                    ChatChannelAdapter chatAdapter,
                                    SessionAuthTokenStore authTokenStore,
                                    InspectorService inspector,
                                    PlaygroundChatSessions sessions) {
        this.tenantGuard = tenantGuard;
        this.workspaceAccess = workspaceAccess;
        this.engine = engine;
        this.entryPoints = entryPoints;
        this.chatAuth = chatAuth;
        this.chatAdapter = chatAdapter;
        this.authTokenStore = authTokenStore;
        this.inspector = inspector;
        this.sessions = sessions;
    }

    /* ----------------------------- start ---------------------------------- */

    @PostMapping("/sessions")
    public Mono<StartResponse> start(@PathVariable("tenantId") String tenantId,
                                     @RequestBody StartRequest req,
                                     ServerWebExchange exchange) {
        return Mono.fromSupplier(() -> {
            workspaceAccess.require(tenantId, exchange, Role.MEMBER);
            tenantGuard.requireExists(tenantId);
            if (req == null || req.entryPointId() == null || req.entryPointId().isBlank()) {
                throw AdminApiException.badRequest("missing_entry_point", "entryPointId required");
            }
            Ids.TenantId tenant = new Ids.TenantId(tenantId);
            ChatEntryPointId epId = new ChatEntryPointId(req.entryPointId());
            ChatEntryPoint ep = entryPoints.requireForTenant(tenant, epId);

            String deviceId = deviceIdFor(req);
            ChatAuthService.Caller caller = callerFor(req, deviceId);
            boolean bearerIdentity = "BEARER".equalsIgnoreCase(req.identityMode());

            ChatAuthService.Resolution res;
            try {
                res = chatAuth.resolveForStart(ep, ep.handle(), deviceId, caller);
            } catch (ChatApiException e) {
                // Request-shape problem (a resolver-exchange chat with an anonymous
                // identity, an AUTH_REQUIRED chat with no bearer): surface the reason
                // inline rather than as a raw 4xx so the operator can correct it.
                return StartResponse.failed(null, e.code(), e.getReason(), ep);
            }

            if (res.rejected()) {
                // No engine session exists (fail-closed), but the operator still needs
                // to SEE why. Mint a synthetic session id and record the trace so the
                // inspector can surface the failing resolver exchange + hints, keyed by
                // tenant + caller hash exactly as a live session would be.
                Ids.SessionId sid = new Ids.SessionId(UUID.randomUUID().toString());
                chatAuth.recordTrace(sid, ep, res, ChannelAuthTrace.TokenState.ABSENT);
                return StartResponse.failed(sid.value(), "resolver_unresolved",
                        "The resolver returned no account for this caller. "
                                + "Open the inspector to see the exchange.", ep);
            }

            Map<String, Object> hints = new HashMap<>();
            hints.put("channel.kind", "chat");
            hints.put("channel.entryPoint", ep.id().value());
            hints.put("channel.handle", ep.handle());
            hints.put("channel.deviceId", deviceId);
            hints.put("channel.locale", req.locale() != null ? req.locale() : ep.branding().defaultLocale());
            hints.put("channel.authMode", ep.authMode().name());

            StartConversationRequest scr = new StartConversationRequest(
                    ep.tenant(), Optional.empty(), "chat", hints);
            StartSessionResult result = engine.start(scr, res.auth().onChannel(TurnChannel.CHAT));
            Ids.SessionId sid = result.session().id();

            res.jwt().ifPresent(jwt -> authTokenStore.persist(sid, jwt));
            chatAuth.recordTrace(sid, ep, res, res.jwt().isPresent()
                    ? ChannelAuthTrace.TokenState.STORED : ChannelAuthTrace.TokenState.ABSENT);

            sessions.put(sid, new PlaygroundChatSessions.Session(
                    tenant, epId, deviceId, caller, res.startedAuthenticated() || bearerIdentity));

            return StartResponse.ok(sid.value(), result, ep);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /* ----------------------------- send ----------------------------------- */

    @PostMapping(value = "/sessions/{sessionId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> sendMessage(@PathVariable("tenantId") String tenantId,
                                                     @PathVariable("sessionId") String sessionId,
                                                     @RequestBody MessageRequest body,
                                                     ServerWebExchange exchange) {
        Ids.SessionId sid = new Ids.SessionId(sessionId);
        return Flux.defer(() -> {
            AuthContext auth = authorizeAndResolve(tenantId, sid, exchange);
            String text = body == null ? "" : body.text();
            return com.yayatechandinnovations.yayaagentic.usage.QuotaRefusals.spoken(engine.send(sid, new UserMessage(text == null ? "" : text, Map.of()), auth.onChannel(TurnChannel.CHAT)))
                    .map(chatAdapter::toSse);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /* ---------------------------- action (tap) ---------------------------- */

    @PostMapping(value = "/sessions/{sessionId}/actions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> action(@PathVariable("tenantId") String tenantId,
                                                @PathVariable("sessionId") String sessionId,
                                                @RequestBody ActionRequest body,
                                                ServerWebExchange exchange) {
        Ids.SessionId sid = new Ids.SessionId(sessionId);
        return Flux.defer(() -> {
            AuthContext auth = authorizeAndResolve(tenantId, sid, exchange);
            return engine.dispatchAction(sid, new Ids.ToolId(body.tool()),
                            body.args() == null ? Map.of() : body.args(), auth.onChannel(TurnChannel.CHAT))
                    .map(chatAdapter::toSse);
        }).subscribeOn(Schedulers.boundedElastic())
          .onErrorMap(ActionNotPermittedException.class,
                  e -> new org.springframework.web.server.ResponseStatusException(
                          org.springframework.http.HttpStatus.FORBIDDEN));
    }

    /* ------------------------------ inspector ----------------------------- */

    @GetMapping("/sessions/{sessionId}/inspector")
    public Mono<InspectorDtos.InspectorSnapshot> inspect(@PathVariable("tenantId") String tenantId,
                                                         @PathVariable("sessionId") String sessionId,
                                                         ServerWebExchange exchange) {
        return Mono.fromSupplier(() -> {
            workspaceAccess.require(tenantId, exchange, Role.MEMBER);
            tenantGuard.requireExists(tenantId);
            return inspector.snapshot(new Ids.SessionId(sessionId));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /* -------------------------------- end --------------------------------- */

    @DeleteMapping("/sessions/{sessionId}")
    public Mono<Void> end(@PathVariable("tenantId") String tenantId,
                          @PathVariable("sessionId") String sessionId,
                          ServerWebExchange exchange) {
        Ids.SessionId sid = new Ids.SessionId(sessionId);
        return Mono.<Void>fromRunnable(() -> {
            workspaceAccess.require(tenantId, exchange, Role.MEMBER);
            tenantGuard.requireExists(tenantId);
            sessions.get(sid).ifPresent(s -> {
                ChatEntryPoint ep = entryPoints.requireForTenant(s.tenant(), s.entryPoint());
                ChatAuthService.Resolution res = chatAuth.resolveForExisting(
                        ep, ep.handle(), s.deviceId(), s.authenticated(), s.caller());
                if (!res.rejected()) engine.end(sid, res.auth());
            });
            sessions.remove(sid);
            chatAuth.wipeTrace(sid);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /* ------------------------------ helpers ------------------------------- */

    /** Gate on operator membership, look up the simulated identity, and re-resolve
     *  auth exactly as the public door does per message — refreshing the inspector
     *  trace on the way. */
    private AuthContext authorizeAndResolve(String tenantId, Ids.SessionId sid, ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.MEMBER);
        tenantGuard.requireExists(tenantId);
        PlaygroundChatSessions.Session s = sessions.get(sid)
                .orElseThrow(ChatApiException::sessionNotFound);
        ChatEntryPoint ep = entryPoints.requireForTenant(s.tenant(), s.entryPoint());
        ChatAuthService.Resolution res = chatAuth.resolveForExisting(
                ep, ep.handle(), s.deviceId(), s.authenticated(), s.caller());
        if (res.rejected()) throw ChatApiException.resolverUnresolved();
        chatAuth.recordTrace(sid, ep, res, res.jwt().isPresent()
                ? ChannelAuthTrace.TokenState.STORED : ChannelAuthTrace.TokenState.ABSENT);
        return res.auth();
    }

    private static String deviceIdFor(StartRequest req) {
        if (req.deviceId() != null && !req.deviceId().isBlank()) return req.deviceId().trim();
        return "pg-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private ChatAuthService.Caller callerFor(StartRequest req, String deviceId) {
        String mode = req.identityMode() == null ? "ANONYMOUS" : req.identityMode().toUpperCase();
        return switch (mode) {
            case "VERIFIED_PHONE" -> {
                if (req.phone() == null || req.phone().isBlank()) {
                    throw AdminApiException.badRequest("missing_phone",
                            "verified-phone identity requires an E.164 phone");
                }
                String name = req.name() == null || req.name().isBlank() ? "Playground Tester" : req.name().trim();
                yield ChatAuthService.Caller.device(
                        new VerifiedDevice(req.phone().trim(), name, deviceId));
            }
            case "BEARER" -> {
                if (req.bearer() == null || req.bearer().isBlank()) {
                    throw AdminApiException.badRequest("missing_bearer",
                            "bearer identity requires a JWT");
                }
                yield ChatAuthService.Caller.bearer(req.bearer().trim());
            }
            default -> ChatAuthService.Caller.anonymous();
        };
    }

    /* ------------------------------- DTOs --------------------------------- */

    /** @param identityMode {@code ANONYMOUS} | {@code VERIFIED_PHONE} | {@code BEARER} */
    public record StartRequest(String entryPointId, String identityMode, String phone,
                               String name, String deviceId, String bearer, String locale) {}

    public record MessageRequest(String text) {}

    public record ActionRequest(String tool, Map<String, Object> args) {}

    /**
     * Mirrors {@link PublicChatDtos.StartResponse} (so the Flutter client reuses
     * the same models) plus a {@code startFailed}/{@code failureCode}/{@code
     * failureMessage} triple for the playground's inspectable-failure case.
     */
    public record StartResponse(String sessionId,
                                boolean startFailed,
                                String failureCode,
                                String failureMessage,
                                String profileId,
                                Integer profileVersion,
                                String greeting,
                                List<String> quickReplies,
                                PublicChatDtos.EntryPointCard entryPoint,
                                Map<String, Object> traits) {

        static StartResponse ok(String sessionId, StartSessionResult result, ChatEntryPoint ep) {
            return new StartResponse(sessionId, false, null, null,
                    result.profile().value(), result.profile().version(),
                    result.greeting(), result.quickReplies(),
                    PublicChatDtos.EntryPointCard.of(ep),
                    result.clientTraits());
        }

        static StartResponse failed(String sessionId, String code, String message, ChatEntryPoint ep) {
            return new StartResponse(sessionId, true, code, message, null, null, null, List.of(),
                    PublicChatDtos.EntryPointCard.of(ep), null);
        }
    }
}
