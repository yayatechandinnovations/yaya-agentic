package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.PublicChatDtos;
import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.auth.Authenticator;
import com.yayatechandinnovations.yayaagentic.auth.anon.AnonymousAuthenticator;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatChannelAdapter;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatEntryPoint;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatEntryPointService;
import com.yayatechandinnovations.yayaagentic.auth.anon.VerifiedDeviceAuthenticator;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatRateLimiter;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatSessionRegistry;
import com.yayatechandinnovations.yayaagentic.channel.chat.EntryPointStatus;
import com.yayatechandinnovations.yayaagentic.channel.chat.verify.DevicePassService;
import com.yayatechandinnovations.yayaagentic.channel.chat.verify.VerifiedDevice;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.engine.ConversationEngine;
import com.yayatechandinnovations.yayaagentic.engine.StartSessionResult;
import com.yayatechandinnovations.yayaagentic.engine.UserMessage;
import com.yayatechandinnovations.yayaagentic.profile.StartConversationRequest;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The public, unauthenticated-capable chat front door ({@code chat-channel-design.md} §9).
 * Deliberately separate from {@code /v1/sessions/*} (the operator playground) and
 * {@code /v1/admin/*} (operator config): it is entry-point-scoped, abuse-gated,
 * and mints its principal from the entry point's auth mode rather than from
 * operator auth. {@code /v1/chat/**} is public by default (not in the operator
 * auth filter's protected set).
 */
@RestController
@RequestMapping("/v1/chat")
public class PublicChatController {

    private static final Duration HOUR = Duration.ofHours(1);
    private static final Duration DAY = Duration.ofHours(24);

    private final ConversationEngine engine;
    private final ChatEntryPointService entryPoints;
    private final AnonymousAuthenticator anon;
    private final ChatSessionRegistry registry;
    private final ChatRateLimiter rateLimiter;
    private final ChatChannelAdapter chatAdapter;
    private final Authenticator authenticator;
    private final DevicePassService devicePasses;
    private final VerifiedDeviceAuthenticator verifiedDeviceAuth;

    public PublicChatController(ConversationEngine engine,
                                ChatEntryPointService entryPoints,
                                AnonymousAuthenticator anon,
                                ChatSessionRegistry registry,
                                ChatRateLimiter rateLimiter,
                                ChatChannelAdapter chatAdapter,
                                Authenticator authenticator,
                                DevicePassService devicePasses,
                                VerifiedDeviceAuthenticator verifiedDeviceAuth) {
        this.engine = engine;
        this.entryPoints = entryPoints;
        this.anon = anon;
        this.registry = registry;
        this.rateLimiter = rateLimiter;
        this.chatAdapter = chatAdapter;
        this.authenticator = authenticator;
        this.devicePasses = devicePasses;
        this.verifiedDeviceAuth = verifiedDeviceAuth;
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

            AuthContext auth = authForStart(ep, handle, deviceId, exchange);

            Map<String, Object> hints = new HashMap<>();
            hints.put("channel.kind", "chat");
            hints.put("channel.entryPoint", ep.id().value());
            hints.put("channel.handle", handle);
            hints.put("channel.deviceId", safe(deviceId));
            hints.put("channel.locale", locale);
            hints.put("channel.authMode", ep.authMode().name());

            StartConversationRequest req = new StartConversationRequest(
                    ep.tenant(), Optional.empty(), "chat", hints);
            StartSessionResult result = engine.start(req, auth);

            registry.register(result.session().id(),
                    new ChatSessionRegistry.Context(ep.tenant().value(), ep.id().value(),
                            handle, deviceId, ep.authMode().name(), startedAuthenticated(ep, exchange)),
                    ep.sessionPolicy().idleTimeout());

            return new PublicChatDtos.StartResponse(
                    result.session().id().value(),
                    result.profile().value(),
                    result.profile().version(),
                    result.greeting(),
                    result.quickReplies(),
                    PublicChatDtos.EntryPointCard.of(ep));
        });
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

            AuthContext auth = authForExisting(ctx, ep, exchange);
            registry.touch(sessionId, ep.sessionPolicy().idleTimeout());
            return engine.send(sessionId, new UserMessage(body.text(), Map.of()), auth)
                    .map(chatAdapter::toSse);
        });
    }

    /* --------------------------- resume / end ----------------------------- */

    @GetMapping("/sessions/{id}")
    public Mono<PublicChatDtos.ResumeResponse> resume(@PathVariable("id") String id) {
        Ids.SessionId sessionId = new Ids.SessionId(id);
        return Mono.fromSupplier(() -> {
            ChatSessionRegistry.Context ctx = registry.get(sessionId)
                    .orElseThrow(ChatApiException::sessionNotFound);
            ChatEntryPoint ep = entryPoints.findByHandle(ctx.handle())
                    .orElseThrow(ChatApiException::sessionNotFound);
            return new PublicChatDtos.ResumeResponse(id, true, PublicChatDtos.EntryPointCard.of(ep));
        });
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
            if (ep.authMode() == com.yayatechandinnovations.yayaagentic.channel.chat.AuthMode.ANONYMOUS_ONLY) {
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
        });
    }

    @PostMapping("/sessions/{id}/end")
    public Mono<Void> end(@PathVariable("id") String id, ServerWebExchange exchange) {
        Ids.SessionId sessionId = new Ids.SessionId(id);
        return Mono.fromRunnable(() -> {
            registry.get(sessionId).ifPresent(ctx -> {
                entryPoints.findByHandle(ctx.handle()).ifPresent(ep ->
                        engine.end(sessionId, authForExisting(ctx, ep, exchange)));
                registry.remove(sessionId);
            });
        });
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

    private AuthContext authForStart(ChatEntryPoint ep, String handle, String deviceId,
                                     ServerWebExchange exchange) {
        Optional<VerifiedDevice> device = verifiedDevice(exchange);
        if (ep.requiresVerifiedPhone() && device.isEmpty()) throw ChatApiException.verificationRequired();

        boolean hasBearer = bearer(exchange) != null;
        // AUTH_REQUIRED → a tenant account (L2) is mandatory.
        if (ep.authMode().requiresTokenToStart()) {
            if (!hasBearer) throw ChatApiException.authRequired();
            return verifiedContext(ep, exchange);
        }
        // AUTH_OPTIONAL with a tenant bearer → L2 wins.
        if (hasBearer && ep.authMode() == com.yayatechandinnovations.yayaagentic.channel.chat.AuthMode.AUTH_OPTIONAL) {
            return verifiedContext(ep, exchange);
        }
        // L1: a phone-verified device beats anonymous.
        if (device.isPresent()) return verifiedDeviceContext(ep, handle, device.get());
        return anonymousContext(ep, handle, deviceId);
    }

    private AuthContext authForExisting(ChatSessionRegistry.Context ctx, ChatEntryPoint ep,
                                        ServerWebExchange exchange) {
        if (!isAnonymous(ctx, exchange)) {
            return verifiedContext(ep, exchange); // L2 tenant account (started authed / upgraded / bearer present)
        }
        Optional<VerifiedDevice> device = verifiedDevice(exchange);
        if (ep.requiresVerifiedPhone() && device.isEmpty()) throw ChatApiException.verificationRequired();
        if (device.isPresent()) return verifiedDeviceContext(ep, ctx.handle(), device.get());
        return anonymousContext(ep, ctx.handle(), ctx.deviceId());
    }

    /** The device pass rides a dedicated {@code X-Device-Pass} header so it never
     *  collides with an {@code Authorization} tenant bearer (L1 vs L2 — §20.1). */
    private Optional<VerifiedDevice> verifiedDevice(ServerWebExchange exchange) {
        String pass = exchange.getRequest().getHeaders().getFirst("X-Device-Pass");
        return devicePasses.verify(pass);
    }

    private AuthContext verifiedDeviceContext(ChatEntryPoint ep, String handle, VerifiedDevice device) {
        Principal p = verifiedDeviceAuth.mint(ep.tenant(), device, handle);
        return new AuthContext(ep.tenant(), Map.of(), Optional.of(p));
    }

    /** Anonymous unless the mode forbids it, the session was upgraded, or a
     *  bearer is presented. */
    private boolean isAnonymous(ChatSessionRegistry.Context ctx, ServerWebExchange exchange) {
        if (ctx.authenticated()) return false; // upgraded via /authenticate (§8.4)
        com.yayatechandinnovations.yayaagentic.channel.chat.AuthMode mode =
                com.yayatechandinnovations.yayaagentic.channel.chat.AuthMode.parse(ctx.authMode());
        if (mode == com.yayatechandinnovations.yayaagentic.channel.chat.AuthMode.ANONYMOUS_ONLY) return true;
        if (mode == com.yayatechandinnovations.yayaagentic.channel.chat.AuthMode.AUTH_REQUIRED) return false;
        return bearer(exchange) == null; // AUTH_OPTIONAL: anon until a bearer appears
    }

    private boolean startedAuthenticated(ChatEntryPoint ep, ServerWebExchange exchange) {
        return switch (ep.authMode()) {
            case ANONYMOUS_ONLY -> false;
            case AUTH_REQUIRED -> true;
            case AUTH_OPTIONAL -> bearer(exchange) != null;
        };
    }

    private AuthContext anonymousContext(ChatEntryPoint ep, String handle, String deviceId) {
        Principal p = anon.mintAnonymous(ep.tenant(), handle, deviceId);
        return new AuthContext(ep.tenant(), Map.of(), Optional.of(p));
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
